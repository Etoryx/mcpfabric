package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Interaction: break/place blocks, use items, attack/use entities, drop held item. */
public final class InteractHandlers {
	private InteractHandlers() {}

	public static void register(RpcRouter router) {
		router.register("interact.breakBlock", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			Direction face = faceToward(pos, p.getEyePosition());
			String mode = ctx.optString("mode", "survival");
			JsonObject o = new JsonObject();
			if ("instant".equals(mode)) {
				boolean broke = gm.destroyBlock(pos);
				swingAttack(p);
				o.addProperty("broke", broke);
				o.addProperty("mode", "instant");
			} else {
				gm.startDestroyBlock(pos, face);
				BotController.get().startMining(pos, face);
				swingAttack(p);
				o.addProperty("started", true);
				o.addProperty("mode", "survival");
				o.addProperty("note", "Mining continues each tick; poll get_block to confirm it broke.");
			}
			return o;
		}));

		router.register("interact.placeBlock", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			Direction face = parseFace(ctx.optString("face", "up"));
			Vec3 hitLoc = new Vec3(
					pos.getX() + 0.5 + face.getStepX() * 0.5,
					pos.getY() + 0.5 + face.getStepY() * 0.5,
					pos.getZ() + 0.5 + face.getStepZ() * 0.5);
			BlockHitResult hit = new BlockHitResult(hitLoc, face, pos, false);
			InteractionResult result = gm.useItemOn(p, InteractionHand.MAIN_HAND, hit);
			swingUse(p);
			JsonObject o = new JsonObject();
			o.addProperty("result", String.valueOf(result));
			return o;
		}));

		router.register("interact.useItem", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			InteractionResult result = gm.useItem(p, InteractionHand.MAIN_HAND);
			JsonObject o = new JsonObject();
			o.addProperty("result", String.valueOf(result));
			return o;
		}));

		router.register("interact.attackEntity", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			Entity e = findEntity(ctx.getString("uuid"));
			gm.attack(p, e);
			swingAttack(p);
			return Json.ok("attacked " + e.getName().getString());
		}));

		router.register("interact.useEntity", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			Entity e = findEntity(ctx.getString("uuid"));
			//? if <26.1 {
			InteractionResult result = gm.interact(p, e, InteractionHand.MAIN_HAND);
			//?} else
			/*InteractionResult result = gm.interact(p, e, new net.minecraft.world.phys.EntityHitResult(e), InteractionHand.MAIN_HAND);*/
			JsonObject o = new JsonObject();
			o.addProperty("result", String.valueOf(result));
			return o;
		}));

		router.register("interact.dropItem", ctx -> ClientMc.call(() -> {
			requireControl();
			LocalPlayer p = ClientMc.player();
			boolean whole = ctx.optBool("wholeStack", false);
			//? if <26.3 {
			p.drop(whole);
			//?} else
			/*ClientMc.gameMode().dropItem(p, whole);*/
			return Json.ok(whole ? "dropped stack" : "dropped one");
		}));
	}

	/** Main-hand swing for attacking / mining. 26.3 made the swing animation an explicit, per-item argument. */
	private static void swingAttack(LocalPlayer p) {
		//? if <26.3 {
		p.swing(InteractionHand.MAIN_HAND);
		//?} else
		/*p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);*/
	}

	/** Main-hand swing for using an item on a block. */
	private static void swingUse(LocalPlayer p) {
		//? if <26.3 {
		p.swing(InteractionHand.MAIN_HAND);
		//?} else
		/*p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getInteractAnimation(), false);*/
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}

	private static Entity findEntity(String uuidStr) throws RpcException {
		UUID uuid;
		try {
			uuid = UUID.fromString(uuidStr);
		} catch (IllegalArgumentException e) {
			throw RpcException.badRequest("Invalid UUID: " + uuidStr);
		}
		for (Entity e : ClientMc.level().entitiesForRendering()) {
			if (e.getUUID().equals(uuid)) return e;
		}
		throw RpcException.notFound("No visible entity with uuid " + uuid);
	}

	private static Direction parseFace(String name) {
		Direction d = Direction.byName(name.toLowerCase());
		return d == null ? Direction.UP : d;
	}

	private static Direction faceToward(BlockPos pos, Vec3 eye) {
		double dx = eye.x - (pos.getX() + 0.5);
		double dy = eye.y - (pos.getY() + 0.5);
		double dz = eye.z - (pos.getZ() + 0.5);
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		if (ax >= ay && ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
		if (az >= ax && az >= ay) return dz > 0 ? Direction.SOUTH : Direction.NORTH;
		return dy > 0 ? Direction.UP : Direction.DOWN;
	}
}
