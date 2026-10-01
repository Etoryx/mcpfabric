package dev.mcpfabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.bridge.MainThread;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.handlers.support.CommandRunner;
import dev.mcpfabric.handlers.support.Gates;
import dev.mcpfabric.handlers.support.Levels;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** World read (block/region/find/time/weather/dimensions/raycast) and write (set/fill/time/weather). */
public final class WorldHandlers {
	private static final int DEFAULT_REGION_CAP = 32768;
	/**
	 * Most positions one region read may visit (256x256x256). The scan runs on the server thread, so
	 * maxBlocks alone (it limits the answer, not the work) let a huge mostly-air region freeze the server.
	 */
	private static final long MAX_REGION_VOLUME = 1L << 24;
	private static final int SCAN_BUDGET = 250_000;

	private WorldHandlers() {}

	public static void register(RpcRouter router) {
		router.register("world.getBlock", ctx -> onServer(server -> {
			ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			if (!level.hasChunkAt(pos)) throw RpcException.notFound("Chunk not loaded at " + pos.toShortString());
			BlockState state = level.getBlockState(pos);
			JsonObject o = Levels.describeBlock(level, pos, state);
			o.addProperty("dimension", Levels.dimensionId(level));
			o.addProperty("blockLight", level.getBrightness(LightLayer.BLOCK, pos));
			o.addProperty("skyLight", level.getBrightness(LightLayer.SKY, pos));
			o.addProperty("hardness", state.getDestroySpeed(level, pos));
			return o;
		}));

		router.register("world.getBlocks", ctx -> onServer(server -> {
			ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
			BlockPos from = blockPos(ctx.getVec3("from"));
			BlockPos to = blockPos(ctx.getVec3("to"));
			int minX = Math.min(from.getX(), to.getX()), minY = Math.min(from.getY(), to.getY()), minZ = Math.min(from.getZ(), to.getZ());
			int maxX = Math.max(from.getX(), to.getX()), maxY = Math.max(from.getY(), to.getY()), maxZ = Math.max(from.getZ(), to.getZ());
			boolean includeAir = ctx.optBool("includeAir", false);
			long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
			if (volume > MAX_REGION_VOLUME) {
				throw RpcException.badRequest("Region has " + volume + " positions; the limit is " + MAX_REGION_VOLUME
						+ " (for example 256x256x256). Split it into smaller regions.");
			}
			int cap = ctx.optInt("maxBlocks", DEFAULT_REGION_CAP);

			JsonArray blocks = new JsonArray();
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			boolean truncated = false;
			outer:
			for (int y = minY; y <= maxY; y++) {
				for (int x = minX; x <= maxX; x++) {
					for (int z = minZ; z <= maxZ; z++) {
						if (blocks.size() >= cap) { truncated = true; break outer; }
						m.set(x, y, z);
						if (!level.hasChunkAt(m)) continue;
						BlockState state = level.getBlockState(m);
						if (!includeAir && state.isAir()) continue;
						JsonObject b = new JsonObject();
						b.addProperty("x", x);
						b.addProperty("y", y);
						b.addProperty("z", z);
						b.addProperty("id", Levels.blockId(state));
						blocks.add(b);
					}
				}
			}
			JsonObject o = new JsonObject();
			o.addProperty("dimension", Levels.dimensionId(level));
			o.addProperty("volume", volume);
			o.addProperty("count", blocks.size());
			o.addProperty("truncated", truncated);
			o.add("blocks", blocks);
			return o;
		}));

		router.register("world.findBlocks", ctx -> onServer(server -> {
			ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
			BlockPos center = blockPos(ctx.getVec3("center"));
			int cx = center.getX(), cy = center.getY(), cz = center.getZ();
			int radius = ctx.getInt("radius");
			int maxResults = ctx.optInt("maxResults", 64);
			Set<String> wanted = new HashSet<>(ctx.getStringList("blockIds"));
			if (wanted.isEmpty()) throw RpcException.badRequest("blockIds must not be empty.");

			List<JsonObject> found = new ArrayList<>();
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			long scanned = 0;
			boolean truncated = false;
			int r2 = radius * radius;
			int searchedRadius = -1;
			// Cube shells from the center outwards (shell s = positions whose largest offset is s), so the
			// scan budget is spent on the positions nearest to the center first.
			outer:
			for (int s = 0; s <= radius; s++) {
				for (int dx = -s; dx <= s; dx++) {
					for (int dy = -s; dy <= s; dy++) {
						// Inside the shell's x/y extent, only its two z faces belong to the shell.
						int dzStep = Math.abs(dx) == s || Math.abs(dy) == s ? 1 : 2 * s;
						for (int dz = -s; dz <= s; dz += dzStep) {
							if (++scanned > SCAN_BUDGET) { truncated = true; break outer; }
							int dist2 = dx * dx + dy * dy + dz * dz;
							if (dist2 > r2) continue;
							m.set(cx + dx, cy + dy, cz + dz);
							if (!level.hasChunkAt(m)) continue;
							BlockState state = level.getBlockState(m);
							String id = Levels.blockId(state);
							if (!wanted.contains(id)) continue;
							JsonObject b = new JsonObject();
							b.addProperty("x", m.getX());
							b.addProperty("y", m.getY());
							b.addProperty("z", m.getZ());
							b.addProperty("id", id);
							b.addProperty("distance", Math.sqrt(dist2));
							found.add(b);
						}
					}
				}
				searchedRadius = s;
			}
			found.sort((a, b) -> Double.compare(a.get("distance").getAsDouble(), b.get("distance").getAsDouble()));
			JsonArray matches = new JsonArray();
			for (int i = 0; i < Math.min(maxResults, found.size()); i++) matches.add(found.get(i));

			JsonObject o = new JsonObject();
			o.addProperty("dimension", Levels.dimensionId(level));
			o.addProperty("totalFound", found.size());
			o.addProperty("returned", matches.size());
			o.addProperty("truncated", truncated);
			// Every position within this distance of the center was searched (the whole radius unless truncated).
			o.addProperty("searchedRadius", searchedRadius);
			o.add("matches", matches);
			return o;
		}));

		router.register("world.getTimeAndWeather", ctx -> onServer(server -> {
			ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
			long dayTime = Levels.dayTime(level) % 24000L;
			if (dayTime < 0) dayTime += 24000L;
			JsonObject o = new JsonObject();
			o.addProperty("dimension", Levels.dimensionId(level));
			o.addProperty("dayTime", dayTime);
			o.addProperty("gameTime", level.getGameTime());
			o.addProperty("day", Levels.dayTime(level) / 24000L);
			o.addProperty("raining", level.isRaining());
			o.addProperty("thundering", level.isThundering());
			return o;
		}));

		router.register("world.getDimensions", ctx -> onServer(server -> {
			JsonArray dims = new JsonArray();
			for (ServerLevel level : server.getAllLevels()) {
				dims.add(Levels.dimensionId(level));
			}
			JsonObject o = new JsonObject();
			o.add("dimensions", dims);
			// The dimension each online player is in; playerDimension when there is exactly one (single player).
			JsonArray players = new JsonArray();
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				JsonObject pl = new JsonObject();
				pl.addProperty("name", p.getName().getString());
				pl.addProperty("dimension", Levels.dimensionId(p.level()));
				players.add(pl);
			}
			o.add("players", players);
			if (players.size() == 1) o.addProperty("playerDimension", players.get(0).getAsJsonObject().get("dimension").getAsString());
			return o;
		}));

		router.register("world.raycast", ctx -> onServer(server -> raycast(server, ctx)));

		// --- writes ----------------------------------------------------------------------------

		router.register("world.setBlock", ctx -> writeCommand(ctx, ctx2 -> {
			MinecraftServer server = ServerHolder.get();
			ServerLevel level = Levels.resolve(server, ctx2.optString("dimension", null));
			int x = (int) Math.floor(ctx2.getDouble("x"));
			int y = (int) Math.floor(ctx2.getDouble("y"));
			int z = (int) Math.floor(ctx2.getDouble("z"));
			String block = ctx2.getString("blockId");
			Gates.dataTags(block);
			return CommandRunner.run(server, level, "setblock " + x + " " + y + " " + z + " " + block).toJson();
		}));

		router.register("world.fill", ctx -> writeCommand(ctx, ctx2 -> {
			MinecraftServer server = ServerHolder.get();
			ServerLevel level = Levels.resolve(server, ctx2.optString("dimension", null));
			BlockPos from = blockPos(ctx2.getVec3("from"));
			BlockPos to = blockPos(ctx2.getVec3("to"));
			String block = ctx2.getString("blockId");
			Gates.dataTags(block);
			String cmd = String.format("fill %d %d %d %d %d %d %s",
					from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(), block);
			return CommandRunner.run(server, level, cmd).toJson();
		}));

		router.register("world.setTime", ctx -> writeCommand(ctx, ctx2 -> {
			MinecraftServer server = ServerHolder.get();
			return CommandRunner.run(server, "time set " + ctx2.getInt("time")).toJson();
		}));

		router.register("world.setWeather", ctx -> writeCommand(ctx, ctx2 -> {
			MinecraftServer server = ServerHolder.get();
			String weather = ctx2.getString("weather");
			if (!weather.equals("clear") && !weather.equals("rain") && !weather.equals("thunder")) {
				throw RpcException.badRequest("weather must be clear|rain|thunder.");
			}
			// The vanilla /weather command parses the duration via TimeArgument, where a bare number is
			// TICKS. Append "s" so durationSeconds is interpreted as seconds, matching the tool schema.
			String cmd = "weather " + weather + (ctx2.has("durationSeconds") ? " " + ctx2.getInt("durationSeconds") + "s" : "");
			return CommandRunner.run(server, cmd).toJson();
		}));
	}

	// --- raycast ---------------------------------------------------------------------------------

	private static JsonElement raycast(MinecraftServer server, RpcContext ctx) throws RpcException {
		ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
		double[] origin = ctx.getVec3("origin");
		Vec3 start = new Vec3(origin[0], origin[1], origin[2]);

		Vec3 dir;
		double[] direction = ctx.optVec3("direction");
		if (direction != null) {
			dir = new Vec3(direction[0], direction[1], direction[2]);
		} else if (ctx.has("yaw") && ctx.has("pitch")) {
			double yaw = Math.toRadians(ctx.getDouble("yaw"));
			double pitch = Math.toRadians(ctx.getDouble("pitch"));
			dir = new Vec3(-Math.cos(pitch) * Math.sin(yaw), -Math.sin(pitch), Math.cos(pitch) * Math.cos(yaw));
		} else {
			throw RpcException.badRequest("Provide either 'direction' or both 'yaw' and 'pitch'.");
		}
		if (dir.lengthSqr() < 1.0e-6) throw RpcException.badRequest("Direction has zero length.");
		dir = dir.normalize();

		double maxDistance = ctx.optDouble("maxDistance", 32.0);
		boolean includeFluids = ctx.optBool("includeFluids", false);
		boolean includeEntities = ctx.optBool("includeEntities", true);

		Vec3 end = start.add(dir.scale(maxDistance));

		// Blocks: outline shapes, as the crosshair sees them (a ray above a bottom slab passes).
		double blockDist = -1;
		BlockPos hitBlock = null;
		BlockState hitState = null;
		Vec3 blockHitPos = null;
		BlockHitResult blockHit = clipLoaded(level, start, end, includeFluids);
		if (blockHit.getType() == HitResult.Type.BLOCK) {
			hitBlock = blockHit.getBlockPos();
			hitState = level.getBlockState(hitBlock);
			blockHitPos = blockHit.getLocation();
			blockDist = blockHitPos.distanceTo(start);
		}

		// Entities in front of the block hit, with the vanilla pick box (bounding box + pick radius).
		double entDist = -1;
		Entity hitEntity = null;
		Vec3 entityHitPos = null;
		if (includeEntities) {
			Vec3 limit = blockHitPos != null ? blockHitPos : end;
			AABB box = new AABB(start, limit).inflate(1.0);
			for (Entity e : level.getEntities((Entity) null, box, ent -> ent.isAlive() && ent.isPickable())) {
				var clip = e.getBoundingBox().inflate(e.getPickRadius()).clip(start, limit);
				if (clip.isPresent()) {
					double d = clip.get().distanceTo(start);
					if (entDist < 0 || d < entDist) {
						entDist = d;
						hitEntity = e;
						entityHitPos = clip.get();
					}
				}
			}
		}

		JsonObject o = new JsonObject();
		o.addProperty("dimension", Levels.dimensionId(level));
		boolean entityFirst = hitEntity != null && (hitBlock == null || entDist <= blockDist);
		if (entityFirst) {
			o.addProperty("hitType", "entity");
			o.addProperty("distance", entDist);
			JsonObject ej = new JsonObject();
			ej.addProperty("uuid", hitEntity.getUUID().toString());
			ej.addProperty("type", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(hitEntity.getType()).toString());
			ej.addProperty("name", hitEntity.getName().getString());
			o.add("entity", ej);
			o.add("hitPos", vec(entityHitPos));
		} else if (hitBlock != null) {
			o.addProperty("hitType", "block");
			o.addProperty("distance", blockDist);
			o.add("block", Levels.describeBlock(level, hitBlock, hitState));
			o.add("hitPos", vec(blockHitPos));
			o.addProperty("face", blockHit.getDirection().getName());
		} else {
			o.addProperty("hitType", "miss");
		}
		return o;
	}

	/**
	 * Vanilla block ray trace ({@code BlockGetter.clip} with outline shapes and optional fluids) that
	 * stops at the first unloaded chunk instead of loading it on the server thread.
	 */
	private static BlockHitResult clipLoaded(ServerLevel level, Vec3 from, Vec3 to, boolean includeFluids) {
		ClipContext clip = new ClipContext(from, to, ClipContext.Block.OUTLINE,
				includeFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, CollisionContext.empty());
		return BlockGetter.traverseBlocks(from, to, clip, (c, pos) -> {
			if (!level.hasChunkAt(pos)) return BlockHitResult.miss(Vec3.atCenterOf(pos), Direction.UP, pos.immutable());
			BlockState state = level.getBlockState(pos);
			BlockHitResult blockHit = level.clipWithInteractionOverride(from, to, pos, c.getBlockShape(state, level, pos), state);
			BlockHitResult fluidHit = c.getFluidShape(level.getFluidState(pos), level, pos).clip(from, to, pos);
			double blockDist = blockHit == null ? Double.MAX_VALUE : from.distanceToSqr(blockHit.getLocation());
			double fluidDist = fluidHit == null ? Double.MAX_VALUE : from.distanceToSqr(fluidHit.getLocation());
			return blockDist <= fluidDist ? blockHit : fluidHit;
		}, c -> BlockHitResult.miss(to, Direction.UP, BlockPos.containing(to)));
	}

	/** The block containing a point, as {@code BlockPos.containing} (floor, not truncation, for negatives). */
	private static BlockPos blockPos(double[] v) {
		return BlockPos.containing(v[0], v[1], v[2]);
	}

	private static JsonObject vec(Vec3 v) {
		JsonObject o = new JsonObject();
		o.addProperty("x", v.x);
		o.addProperty("y", v.y);
		o.addProperty("z", v.z);
		return o;
	}

	// --- helpers ---------------------------------------------------------------------------------

	private interface ServerWork {
		JsonElement run(MinecraftServer server) throws RpcException;
	}

	private static JsonElement onServer(ServerWork work) throws RpcException {
		MinecraftServer server = ServerHolder.get();
		if (server == null) throw RpcException.noServer();
		return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> work.run(server));
	}

	private interface CtxWork {
		JsonElement run(RpcContext ctx) throws RpcException;
	}

	private static JsonElement writeCommand(RpcContext ctx, CtxWork work) throws RpcException {
		if (!McpFabric.config().enableWorldWrite) {
			throw RpcException.unavailable("World writes are disabled in mcpfabric.config.json (enableWorldWrite=false).");
		}
		MinecraftServer server = ServerHolder.get();
		if (server == null) throw RpcException.noServer();
		return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> work.run(ctx));
	}
}
