package dev.mcpfabric.client.handlers;

import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.handlers.support.Gates;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
//? if <26.1 {
import net.minecraft.world.inventory.ClickType;
//?}

/** Inventory manipulation: hotbar selection, dropping a slot, swapping two slots. */
public final class InventoryHandlers {
	private InventoryHandlers() {}

	public static void register(RpcRouter router) {
		router.register("inventory.selectHotbar", ctx -> ClientMc.call(() -> {
			Gates.playerControl();
			int slot = ctx.getInt("slot");
			if (slot < 0 || slot > 8) throw RpcException.badRequest("Hotbar slot must be 0-8.");
			LocalPlayer p = ClientMc.player();
			//? if >=1.21.5 {
			p.getInventory().setSelectedSlot(slot);
			//?} else
			/*p.getInventory().selected = slot;*/
			p.connection.send(new ServerboundSetCarriedItemPacket(slot));
			return Json.ok("selected slot " + slot);
		}));

		router.register("inventory.dropSlot", ctx -> ClientMc.call(() -> {
			Gates.playerControl();
			LocalPlayer p = ClientMc.player();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			int menuSlot = toMenuSlot(ctx.getInt("slot"));
			boolean whole = ctx.optBool("wholeStack", true);
			containerClick(gm, p.inventoryMenu.containerId, menuSlot, whole ? 1 : 0, true, p);
			return Json.ok("dropped slot");
		}));

		router.register("inventory.swapSlots", ctx -> ClientMc.call(() -> {
			Gates.playerControl();
			LocalPlayer p = ClientMc.player();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			int a = toMenuSlot(ctx.getInt("slotA"));
			int b = toMenuSlot(ctx.getInt("slotB"));
			int id = p.inventoryMenu.containerId;
			containerClick(gm, id, a, 0, false, p);
			containerClick(gm, id, b, 0, false, p);
			containerClick(gm, id, a, 0, false, p);
			return Json.ok("swapped");
		}));
	}

	/**
	 * Click a container slot. {@code handleInventoryMouseClick(..., ClickType, ...)} became
	 * {@code handleContainerInput(..., ContainerInput, ...)} in 26.1 (same constant names).
	 */
	private static void containerClick(MultiPlayerGameMode gm, int containerId, int slot, int button, boolean throwItem, LocalPlayer p) {
		//? if <26.1 {
		gm.handleInventoryMouseClick(containerId, slot, button, throwItem ? ClickType.THROW : ClickType.PICKUP, p);
		//?} else
		/*gm.handleContainerInput(containerId, slot, button, throwItem ? net.minecraft.world.inventory.ContainerInput.THROW : net.minecraft.world.inventory.ContainerInput.PICKUP, p);*/
	}

	/**
	 * Map a player-inventory index to the slot index inside the player's {@code InventoryMenu}.
	 * Convention: 0-8 hotbar, 9-35 main, 36-39 armor (helmet..boots), 40 offhand.
	 */
	private static int toMenuSlot(int inv) throws RpcException {
		if (inv >= 0 && inv <= 8) return 36 + inv;       // hotbar -> menu 36-44
		if (inv >= 9 && inv <= 35) return inv;            // main -> menu 9-35
		if (inv >= 36 && inv <= 39) return 5 + (inv - 36); // armor -> menu 5-8 (helmet..boots)
		if (inv == 40) return 45;                         // offhand -> menu 45
		throw RpcException.badRequest("Inventory slot out of range (0-40): " + inv);
	}
}
