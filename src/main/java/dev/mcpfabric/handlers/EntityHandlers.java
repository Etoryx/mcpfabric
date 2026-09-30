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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Entity query/get plus summon/remove. */
public final class EntityHandlers {
	private EntityHandlers() {}

	public static void register(RpcRouter router) {
		router.register("entities.query", ctx -> onServer(server -> {
			ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
			double radius = ctx.optDouble("radius", 32.0);
			Vec3 center;
			JsonObject c = ctx.optObject("center");
			if (c != null) {
				center = new Vec3(c.get("x").getAsDouble(), c.get("y").getAsDouble(), c.get("z").getAsDouble());
			} else {
				var spawn = Levels.spawnPos(level);
				center = new Vec3(spawn.getX() + 0.5, spawn.getY() + 0.5, spawn.getZ() + 0.5);
			}
			boolean includePlayers = ctx.optBool("includePlayers", true);
			boolean onlyLiving = ctx.optBool("onlyLiving", false);
			int maxResults = ctx.optInt("maxResults", 100);
			Set<String> types = new HashSet<>(ctx.getStringList("types"));

			AABB box = new AABB(center, center).inflate(radius);
			List<Entity> entities = level.getEntities((Entity) null, box, e -> {
				if (!includePlayers && e instanceof Player) return false;
				if (onlyLiving && !(e instanceof LivingEntity)) return false;
				if (!types.isEmpty() && !types.contains(typeId(e))) return false;
				return e.position().distanceTo(center) <= radius;
			});
			entities.sort((a, b) -> Double.compare(a.position().distanceTo(center), b.position().distanceTo(center)));

			JsonArray arr = new JsonArray();
			for (int i = 0; i < Math.min(maxResults, entities.size()); i++) {
				arr.add(describe(entities.get(i), center));
			}
			JsonObject o = new JsonObject();
			o.addProperty("dimension", Levels.dimensionId(level));
			o.addProperty("total", entities.size());
			o.addProperty("returned", arr.size());
			o.add("entities", arr);
			return o;
		}));

		router.register("entities.get", ctx -> onServer(server -> {
			UUID uuid = parseUuid(ctx.getString("uuid"));
			for (ServerLevel level : server.getAllLevels()) {
				Entity e = level.getEntity(uuid);
				if (e != null) {
					JsonObject o = describe(e, e.position());
					o.addProperty("dimension", Levels.dimensionId(level));
					addExtra(o, e);
					return o;
				}
			}
			throw RpcException.notFound("No entity with uuid " + uuid);
		}));

		router.register("entities.summon", ctx -> writeOnServer(server -> {
			ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
			String type = ctx.getString("type");
			double x = ctx.getDouble("x"), y = ctx.getDouble("y"), z = ctx.getDouble("z");
			String nbt = ctx.optString("nbt", null);
			Gates.dataTags(nbt);
			String cmd = "summon " + type + " " + x + " " + y + " " + z + (nbt != null ? " " + nbt : "");
			return CommandRunner.run(server, level, cmd).toJson();
		}));

		router.register("entities.remove", ctx -> writeOnServer(server -> {
			UUID uuid = parseUuid(ctx.getString("uuid"));
			for (ServerLevel level : server.getAllLevels()) {
				Entity e = level.getEntity(uuid);
				if (e != null) {
					if (e instanceof Player) throw RpcException.badRequest("Refusing to remove a player entity.");
					e.discard();
					JsonObject o = new JsonObject();
					o.addProperty("removed", true);
					o.addProperty("uuid", uuid.toString());
					return o;
				}
			}
			throw RpcException.notFound("No entity with uuid " + uuid);
		}));
	}

	private static String typeId(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
	}

	private static JsonObject describe(Entity e, Vec3 from) {
		JsonObject o = new JsonObject();
		o.addProperty("uuid", e.getUUID().toString());
		o.addProperty("type", typeId(e));
		o.addProperty("name", e.getName().getString());
		o.addProperty("isPlayer", e instanceof Player);
		o.addProperty("x", e.getX());
		o.addProperty("y", e.getY());
		o.addProperty("z", e.getZ());
		o.addProperty("distance", e.position().distanceTo(from));
		if (e instanceof LivingEntity le) {
			o.addProperty("health", le.getHealth());
			o.addProperty("maxHealth", le.getMaxHealth());
		}
		return o;
	}

	private static void addExtra(JsonObject o, Entity e) {
		o.addProperty("onGround", e.onGround());
		Vec3 v = e.getDeltaMovement();
		JsonObject motion = new JsonObject();
		motion.addProperty("x", v.x);
		motion.addProperty("y", v.y);
		motion.addProperty("z", v.z);
		o.add("motion", motion);
		o.addProperty("yaw", e.getYRot());
		o.addProperty("pitch", e.getXRot());
	}

	private static UUID parseUuid(String s) throws RpcException {
		try {
			return UUID.fromString(s);
		} catch (IllegalArgumentException ex) {
			throw RpcException.badRequest("Invalid UUID: " + s);
		}
	}

	// --- scheduling helpers ----------------------------------------------------------------------

	private interface ServerWork {
		JsonElement run(MinecraftServer server) throws RpcException;
	}

	private static JsonElement onServer(ServerWork work) throws RpcException {
		MinecraftServer server = ServerHolder.get();
		if (server == null) throw RpcException.noServer();
		return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> work.run(server));
	}

	private static JsonElement writeOnServer(ServerWork work) throws RpcException {
		if (!McpFabric.config().enableWorldWrite) {
			throw RpcException.unavailable("World writes are disabled in mcpfabric.config.json (enableWorldWrite=false).");
		}
		return onServer(work);
	}
}
