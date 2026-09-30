package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.handlers.support.Levels;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.UUID;

/** Interaction: break/place blocks, use items, attack/use entities, drop held item. */
public final class InteractHandlers {
	/** How long break/place wait for the integrated server to apply a break or a block placement. */
	private static final long SERVER_WAIT_MS = 1000;
	/**
	 * Wait when no placement is expected (the held item is not a block: activating a block, using a
	 * tool): long enough for the server to handle the click, short enough that two quick clicks stay
	 * within an item cooldown of 1 s.
	 */
	private static final long SETTLE_MS = 150;
	/** Extra reach the server allows for entity interactions (ServerGamePacketListenerImpl uses 3.0). */
	private static final double ENTITY_REACH_BUFFER = 3.0;

	private InteractHandlers() {}

	/** What a client action needs to be checked on the integrated server afterwards. */
	private record Aim(ResourceKey<Level> dimension, UUID player, boolean creative, boolean inReach, double reach,
			boolean holdingBlock) {}

	public static void register(RpcRouter router) {
		router.register("interact.breakBlock", ctx -> {
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			String mode = ctx.optString("mode", "survival");
			if (!"instant".equals(mode)) {
				return ClientMc.call(() -> {
					requireControl();
					MultiPlayerGameMode gm = ClientMc.gameMode();
					LocalPlayer p = ClientMc.player();
					Direction face = faceToward(pos, p.getEyePosition());
					gm.startDestroyBlock(pos, face);
					BotController.get().startMining(pos, face);
					swingAttack(p);
					JsonObject o = new JsonObject();
					o.addProperty("started", true);
					o.addProperty("mode", "survival");
					o.addProperty("note", "Mining continues each tick; poll get_block to confirm it broke.");
					return o;
				});
			}
			// Instant: the client alone cannot break a block (destroyBlock only predicts it and sends
			// nothing). In creative the start-destroy packet breaks it at once; otherwise the integrated
			// server breaks it, which is a world write.
			Aim aim = ClientMc.call(() -> {
				requireControl();
				MultiPlayerGameMode gm = ClientMc.gameMode();
				LocalPlayer p = ClientMc.player();
				boolean creative = gm.getPlayerMode() == GameType.CREATIVE;
				if (creative) {
					gm.startDestroyBlock(pos, faceToward(pos, p.getEyePosition()));
					swingAttack(p);
				}
				return aim(p, pos, creative);
			});
			ServerLevel level = ServerSide.level(aim.dimension());
			JsonObject o = new JsonObject();
			o.addProperty("mode", "instant");
			if (aim.creative()) {
				o.addProperty("method", "creative");
				if (level == null) {
					o.add("broke", null);
					o.addProperty("note", "Connected to a remote server: the break was sent but cannot be confirmed; poll get_block.");
					return o;
				}
				BlockState after = ServerSide.await(level, pos, BlockState::isAir, SERVER_WAIT_MS);
				o.addProperty("broke", after.isAir());
				o.addProperty("serverBlock", Levels.blockId(after));
				return o;
			}
			if (level == null || !McpFabric.config().enableWorldWrite) {
				throw RpcException.badRequest("mode 'instant' needs creative mode, or survival on an integrated server with "
						+ "enableWorldWrite=true. Use mode 'survival' to mine the block.");
			}
			boolean broke = ServerSide.call(level, () -> {
				ServerPlayer breaker = level.getServer().getPlayerList().getPlayer(aim.player());
				return level.destroyBlock(pos, true, breaker);
			});
			BlockState after = ServerSide.call(level, () -> level.getBlockState(pos));
			o.addProperty("method", "server");
			o.addProperty("broke", broke && after.isAir());
			o.addProperty("serverBlock", Levels.blockId(after));
			return o;
		});

		router.register("interact.placeBlock", ctx -> {
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			Direction face = parseFace(ctx.optString("face", "up"));
			Aim aim = ClientMc.call(() -> {
				requireControl();
				return aim(ClientMc.player(), pos, false);
			});
			JsonObject o = new JsonObject();
			// The server refuses clicks beyond the interaction range: do not act (or report a client
			// prediction) for them.
			if (!aim.inReach()) {
				o.addProperty("result", "OUT_OF_REACH");
				o.addProperty("placed", false);
				o.addProperty("note", String.format(java.util.Locale.ROOT,
						"The block is beyond the interaction range (%.1f blocks); move closer first.", aim.reach()));
				return o;
			}
			ServerLevel level = ServerSide.level(aim.dimension());
			BlockPos target = null;
			BlockState before = null;
			if (level != null) {
				BlockState clicked = ServerSide.call(level, () -> level.getBlockState(pos));
				target = clicked.canBeReplaced() ? pos : pos.relative(face);
				BlockPos targetPos = target;
				before = ServerSide.call(level, () -> level.getBlockState(targetPos));
			}
			String result = ClientMc.call(() -> {
				requireControl();
				MultiPlayerGameMode gm = ClientMc.gameMode();
				LocalPlayer p = ClientMc.player();
				Vec3 hitLoc = new Vec3(
						pos.getX() + 0.5 + face.getStepX() * 0.5,
						pos.getY() + 0.5 + face.getStepY() * 0.5,
						pos.getZ() + 0.5 + face.getStepZ() * 0.5);
				BlockHitResult hit = new BlockHitResult(hitLoc, face, pos, false);
				InteractionResult r = gm.useItemOn(p, InteractionHand.MAIN_HAND, hit);
				swingUse(p);
				return String.valueOf(r);
			});
			// The client's result is a prediction; with an integrated server, report what it did.
			o.addProperty("result", result);
			if (level == null) {
				o.add("placed", null);
				o.addProperty("note", "Connected to a remote server: 'result' is the client's prediction; poll get_block to confirm.");
				return o;
			}
			BlockState was = before;
			BlockState after = ServerSide.await(level, target, state -> state != was, aim.holdingBlock() ? SERVER_WAIT_MS : SETTLE_MS);
			o.addProperty("placed", after != before);
			JsonObject placedAt = new JsonObject();
			placedAt.addProperty("x", target.getX());
			placedAt.addProperty("y", target.getY());
			placedAt.addProperty("z", target.getZ());
			placedAt.addProperty("serverBlock", Levels.blockId(after));
			o.add("target", placedAt);
			return o;
		});

		router.register("interact.useItem", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			InteractionResult result = gm.useItem(p, InteractionHand.MAIN_HAND);
			JsonObject o = new JsonObject();
			o.addProperty("result", String.valueOf(result));
			return o;
		}));

		router.register("interact.attackEntity", ctx -> {
			String uuid = ctx.getString("uuid");
			EntityAim aim = ClientMc.call(() -> {
				requireControl();
				LocalPlayer p = ClientMc.player();
				Entity e = findEntity(uuid);
				return new EntityAim(ClientMc.level().dimension(), e.getUUID(), e.getName().getString(),
						ClientMc.canReachEntity(p, e, ENTITY_REACH_BUFFER));
			});
			if (!aim.inReach()) return outOfReach(aim);
			// The client only predicts the hit: with an integrated server, report the target's health there.
			ServerLevel level = ServerSide.level(aim.dimension());
			Float before = level == null ? null : ServerSide.call(level, () -> serverHealth(level, aim.entity()));
			ClientMc.call(() -> {
				LocalPlayer p = ClientMc.player();
				ClientMc.gameMode().attack(p, findEntity(uuid));
				swingAttack(p);
				return null;
			});
			JsonObject o = Json.ok("attacked " + aim.name());
			if (level == null) {
				o.addProperty("note", "Connected to a remote server: the attack was sent but its effect cannot be confirmed.");
				return o;
			}
			Float after = before;
			long deadline = System.nanoTime() + SETTLE_MS * 1_000_000L;
			while (System.nanoTime() < deadline && Objects.equals(after, before)) {
				pause(25);
				after = ServerSide.call(level, () -> serverHealth(level, aim.entity()));
			}
			o.addProperty("removed", after == null);
			if (before != null && !before.isNaN()) {
				// A living target: did the server take health off it?
				o.addProperty("damaged", after == null || after < before);
				if (after != null) o.addProperty("serverHealth", after);
			}
			return o;
		});

		router.register("interact.useEntity", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			Entity e = findEntity(ctx.getString("uuid"));
			// The server ignores interactions beyond its range: do not send (or report a prediction for) them.
			if (!ClientMc.canReachEntity(p, e, ENTITY_REACH_BUFFER)) {
				return outOfReach(new EntityAim(ClientMc.level().dimension(), e.getUUID(), e.getName().getString(), false));
			}
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

	/** An entity action's target, read on the client thread. */
	private record EntityAim(ResourceKey<Level> dimension, UUID entity, String name, boolean inReach) {}

	private static JsonObject outOfReach(EntityAim aim) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", false);
		o.addProperty("result", "OUT_OF_REACH");
		o.addProperty("note", aim.name() + " is beyond the interaction range; move closer first.");
		return o;
	}

	/** Health of an entity on the server (NaN when it has none), or null once it is gone (killed, broken, despawned). */
	private static Float serverHealth(ServerLevel level, UUID uuid) {
		Entity e = level.getEntity(uuid);
		if (e == null || !e.isAlive()) return null;
		return e instanceof LivingEntity le ? le.getHealth() : Float.NaN;
	}

	private static void pause(long ms) throws RpcException {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RpcException("interrupted", "Interrupted while waiting for the server.");
		}
	}

	/** Player, dimension and reach for a block action, read on the client thread. */
	private static Aim aim(LocalPlayer p, BlockPos pos, boolean creative) throws RpcException {
		// Same margin the server allows for block interactions.
		boolean inReach = ClientMc.canReachBlock(p, pos, 1.0);
		boolean holdingBlock = p.getMainHandItem().getItem() instanceof BlockItem;
		return new Aim(ClientMc.level().dimension(), p.getUUID(), creative, inReach, p.blockInteractionRange(), holdingBlock);
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
