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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** World read (block/region/find/time/weather/dimensions/raycast) and write (set/fill/time/weather). */
public final class WorldHandlers {
	private static final int DEFAULT_REGION_CAP = 32768;
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
			JsonObject from = ctx.getObject("from");
			JsonObject to = ctx.getObject("to");
			int x1 = from.get("x").getAsInt(), y1 = from.get("y").getAsInt(), z1 = from.get("z").getAsInt();
			int x2 = to.get("x").getAsInt(), y2 = to.get("y").getAsInt(), z2 = to.get("z").getAsInt();
			int minX = Math.min(x1, x2), minY = Math.min(y1, y2), minZ = Math.min(z1, z2);
			int maxX = Math.max(x1, x2), maxY = Math.max(y1, y2), maxZ = Math.max(z1, z2);
			boolean includeAir = ctx.optBool("includeAir", false);
			long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
			int cap = ctx.optInt("maxBlocks", DEFAULT_REGION_CAP);

			JsonArray blocks = new JsonArray();
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			boolean truncated = false;
			long scanned = 0;
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
						scanned++;
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
			JsonObject center = ctx.getObject("center");
			int cx = center.get("x").getAsInt(), cy = center.get("y").getAsInt(), cz = center.get("z").getAsInt();
			int radius = ctx.getInt("radius");
			int maxResults = ctx.optInt("maxResults", 64);
			Set<String> wanted = new HashSet<>(ctx.getStringList("blockIds"));
			if (wanted.isEmpty()) throw RpcException.badRequest("blockIds must not be empty.");

			List<JsonObject> found = new ArrayList<>();
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			long scanned = 0;
			boolean truncated = false;
			int r2 = radius * radius;
			outer:
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dy = -radius; dy <= radius; dy++) {
					for (int dz = -radius; dz <= radius; dz++) {
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
			found.sort((a, b) -> Double.compare(a.get("distance").getAsDouble(), b.get("distance").getAsDouble()));
			JsonArray matches = new JsonArray();
			for (int i = 0; i < Math.min(maxResults, found.size()); i++) matches.add(found.get(i));

			JsonObject o = new JsonObject();
			o.addProperty("dimension", Levels.dimensionId(level));
			o.addProperty("totalFound", found.size());
			o.addProperty("returned", matches.size());
			o.addProperty("truncated", truncated);
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
			JsonObject from = ctx2.getObject("from");
			JsonObject to = ctx2.getObject("to");
			String block = ctx2.getString("blockId");
			Gates.dataTags(block);
			String cmd = String.format("fill %d %d %d %d %d %d %s",
					from.get("x").getAsInt(), from.get("y").getAsInt(), from.get("z").getAsInt(),
					to.get("x").getAsInt(), to.get("y").getAsInt(), to.get("z").getAsInt(), block);
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
		JsonObject origin = ctx.getObject("origin");
		Vec3 start = new Vec3(origin.get("x").getAsDouble(), origin.get("y").getAsDouble(), origin.get("z").getAsDouble());

		Vec3 dir;
		JsonObject dirObj = ctx.optObject("direction");
		if (dirObj != null) {
			dir = new Vec3(dirObj.get("x").getAsDouble(), dirObj.get("y").getAsDouble(), dirObj.get("z").getAsDouble());
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

		// Block traversal by stepping.
		double blockDist = -1;
		BlockPos hitBlock = null;
		BlockState hitState = null;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		final double step = 0.1;
		for (double t = 0; t <= maxDistance; t += step) {
			Vec3 p = start.add(dir.scale(t));
			m.set((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
			if (!level.hasChunkAt(m)) break;
			BlockState state = level.getBlockState(m);
			boolean solid = !state.isAir() || (includeFluids && !state.getFluidState().isEmpty());
			if (solid) {
				blockDist = t;
				hitBlock = m.immutable();
				hitState = state;
				break;
			}
		}

		// Entity scan.
		double entDist = -1;
		Entity hitEntity = null;
		if (includeEntities) {
			Vec3 end = start.add(dir.scale(maxDistance));
			AABB box = new AABB(start, end).inflate(1.0);
			for (Entity e : level.getEntities((Entity) null, box, ent -> ent.isAlive() && ent.isPickable())) {
				AABB eb = e.getBoundingBox().inflate(0.3);
				var clip = eb.clip(start, end);
				if (clip.isPresent()) {
					double d = clip.get().distanceTo(start);
					if (entDist < 0 || d < entDist) {
						entDist = d;
						hitEntity = e;
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
			Vec3 hp = start.add(dir.scale(entDist));
			o.add("hitPos", vec(hp));
		} else if (hitBlock != null) {
			o.addProperty("hitType", "block");
			o.addProperty("distance", blockDist);
			o.add("block", Levels.describeBlock(level, hitBlock, hitState));
			o.add("hitPos", vec(start.add(dir.scale(blockDist))));
		} else {
			o.addProperty("hitType", "miss");
		}
		return o;
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
