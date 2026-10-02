package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Container interaction for the agent runtime: open the block at a position, read the open menu,
 * click / shift-click slots, bulk-transfer items, close. "Container slots" are every menu slot that
 * does not belong to the player's inventory — with no screen open that is the 2×2 crafting grid
 * (slot 0 result, 1-4 grid), in a crafting table slot 0 and 1-9.
 */
public final class ContainerHandlers {
	private ContainerHandlers() {}

	public static void register(RpcRouter router) {
		router.register("container.open", ctx -> ClientMc.call(() -> {
			requireControl();
			LocalPlayer p = ClientMc.player();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			// The server ignores clicks beyond the interaction range; the client would only predict.
			if (!ClientMc.canReachBlock(p, pos, 1.0)) {
				throw RpcException.badRequest(String.format(Locale.ROOT,
						"The block is beyond the interaction range (%.1f blocks); move closer first.", p.blockInteractionRange()));
			}
			// A right-click on any other block would use the held item on it (place a block, flip a lever).
			if (!opensMenu(ClientMc.level(), pos)) {
				throw RpcException.badRequest("The block at " + pos.toShortString() + " ("
						+ BuiltInRegistries.BLOCK.getKey(ClientMc.level().getBlockState(pos).getBlock()) + ") does not open a menu.");
			}
			Vec3 eye = p.getEyePosition();
			Direction face = InteractHandlers.faceToward(pos, eye);
			Vec3 hit = new Vec3(pos.getX() + 0.5 + face.getStepX() * 0.5, pos.getY() + 0.5 + face.getStepY() * 0.5, pos.getZ() + 0.5 + face.getStepZ() * 0.5);
			if (p.isShiftKeyDown()) {
				throw RpcException.badRequest("Stop sneaking first: a sneaking right-click places the held item instead of opening the block.");
			}
			InteractionResult result = gm.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
			JsonObject o = new JsonObject();
			o.addProperty("result", String.valueOf(result));
			o.addProperty("note", "The menu opens once the server answers; poll container.state.");
			return o;
		}));

		router.register("container.state", ctx -> ClientMc.call(() -> state(ClientMc.player())));

		router.register("container.click", ctx -> ClientMc.call(() -> {
			requireControl();
			LocalPlayer p = ClientMc.player();
			AbstractContainerMenu menu = p.containerMenu;
			int slot = ctx.getInt("slot");
			if (slot < 0 || slot >= menu.slots.size()) throw RpcException.badRequest("Slot out of range 0-" + (menu.slots.size() - 1) + ": " + slot);
			String mode = ctx.optString("mode", "pickup").toLowerCase(Locale.ROOT);
			if (!mode.equals("pickup") && !mode.equals("quick_move") && !mode.equals("throw") && !mode.equals("swap")) {
				throw RpcException.badRequest("mode must be pickup, quick_move, throw or swap.");
			}
			InventoryHandlers.containerClick(ClientMc.gameMode(), menu.containerId, slot, ctx.optInt("button", 0), mode, p);
			return state(p);
		}));

		router.register("container.transfer", ctx -> ClientMc.call(() -> {
			requireControl();
			LocalPlayer p = ClientMc.player();
			AbstractContainerMenu menu = p.containerMenu;
			if (menu == p.inventoryMenu) throw RpcException.badRequest("No container is open.");
			boolean take = "take".equals(ctx.getString("direction"));
			String item = ctx.optString("item", null);
			int limit = ctx.has("count") ? ctx.getInt("count") : Integer.MAX_VALUE;
			JsonObject moved = new JsonObject();
			int total = 0;
			for (Slot slot : menu.slots) {
				if (total >= limit) break;
				boolean playerSlot = slot.container == p.getInventory();
				if (take == playerSlot || slot instanceof ResultSlot || !slot.hasItem()) continue;
				ItemStack stack = slot.getItem();
				String id = itemId(stack);
				if (item != null && !item.equals(id)) continue;
				int before = stack.getCount();
				InventoryHandlers.containerClick(ClientMc.gameMode(), menu.containerId, slot.index, 0, "quick_move", p);
				int after = slot.hasItem() && itemId(slot.getItem()).equals(id) ? slot.getItem().getCount() : 0;
				int n = before - after;
				if (n > 0) {
					total += n;
					moved.addProperty(id, (moved.has(id) ? moved.get(id).getAsInt() : 0) + n);
				}
			}
			JsonObject o = new JsonObject();
			o.add("moved", moved);
			return o;
		}));

		router.register("container.close", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			if (p.containerMenu != p.inventoryMenu || currentScreen() != null) {
				p.closeContainer();
			}
			return Json.ok("closed");
		}));
	}

	/** Whether right-clicking the block opens a menu: containers, workstations, modded machines. */
	static boolean opensMenu(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getMenuProvider(level, pos) != null) return true;
		if (level.getBlockEntity(pos) instanceof MenuProvider) return true;
		// The ender chest opens the player's own ender inventory and has no menu provider.
		return state.getBlock() instanceof EnderChestBlock;
	}

	static JsonObject state(LocalPlayer p) {
		AbstractContainerMenu menu = p.containerMenu;
		JsonObject o = new JsonObject();
		boolean open = menu != p.inventoryMenu;
		o.addProperty("open", open);
		o.addProperty("containerId", menu.containerId);
		if (open) {
			try {
				o.addProperty("type", String.valueOf(BuiltInRegistries.MENU.getKey(menu.getType())));
			} catch (UnsupportedOperationException ignored) {
				// menus without a registered type (the player inventory) throw here
			}
			Screen screen = currentScreen();
			if (screen != null) o.addProperty("title", screen.getTitle().getString());
		}
		JsonArray slots = new JsonArray();
		int size = 0;
		for (Slot slot : menu.slots) {
			if (slot.container == p.getInventory()) continue;
			size++;
			if (!slot.hasItem()) continue;
			JsonObject s = new JsonObject();
			s.addProperty("slot", slot.index);
			s.addProperty("id", itemId(slot.getItem()));
			s.addProperty("count", slot.getItem().getCount());
			slots.add(s);
		}
		o.addProperty("size", size);
		o.add("slots", slots);
		return o;
	}

	/** The open screen. It moved from {@code Minecraft.screen} to {@code Gui.screen()} in 26.2. */
	private static Screen currentScreen() {
		//? if <26.2 {
		return Minecraft.getInstance().screen;
		//?} else
		/*return Minecraft.getInstance().gui.screen();*/
	}

	static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}
}
