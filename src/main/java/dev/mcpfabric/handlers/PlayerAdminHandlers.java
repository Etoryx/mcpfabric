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
import dev.mcpfabric.handlers.support.Gates;
import dev.mcpfabric.handlers.support.CommandRunner;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Server-side player administration: list/get + teleport/gamemode/give/effect/message/kick. */
public final class PlayerAdminHandlers {
	private PlayerAdminHandlers() {}

	public static void register(RpcRouter router) {
		router.register("players.list", ctx -> onServer(server -> {
			JsonArray arr = new JsonArray();
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				arr.add(describe(p));
			}
			JsonObject o = new JsonObject();
			o.addProperty("count", arr.size());
			o.add("players", arr);
			return o;
		}));

		router.register("players.get", ctx -> onServer(server -> describe(require(server, ctx.getString("player")))));

		router.register("players.teleport", ctx -> onServer(server -> {
			Gates.commands();
			ServerPlayer p = require(server, ctx.getString("player"));
			String name = playerName(p);
			double x = ctx.getDouble("x"), y = ctx.getDouble("y"), z = ctx.getDouble("z");
			StringBuilder tp = new StringBuilder("teleport ").append(name).append(' ')
					.append(CommandRunner.num(x)).append(' ').append(CommandRunner.num(y)).append(' ').append(CommandRunner.num(z));
			if (ctx.has("yaw") && ctx.has("pitch")) {
				tp.append(' ').append(CommandRunner.num(ctx.getDouble("yaw")))
						.append(' ').append(CommandRunner.num(ctx.getDouble("pitch")));
			}
			String cmd = tp.toString();
			if (ctx.has("dimension")) {
				cmd = "execute in " + ctx.getString("dimension") + " run " + cmd;
			}
			return CommandRunner.run(server, cmd).toJson();
		}));

		router.register("players.setGameMode", ctx -> onServer(server -> {
			Gates.commands();
			ServerPlayer p = require(server, ctx.getString("player"));
			String mode = ctx.getString("mode");
			return CommandRunner.run(server, "gamemode " + mode + " " + playerName(p)).toJson();
		}));

		router.register("players.give", ctx -> onServer(server -> {
			Gates.commands();
			ServerPlayer p = require(server, ctx.getString("player"));
			String item = ctx.getString("itemId") + ctx.optString("nbt", "");
			int count = ctx.optInt("count", 1);
			return CommandRunner.run(server, "give " + playerName(p) + " " + item + " " + count).toJson();
		}));

		router.register("players.applyEffect", ctx -> onServer(server -> {
			Gates.commands();
			ServerPlayer p = require(server, ctx.getString("player"));
			String effect = ctx.getString("effectId");
			int seconds = ctx.optInt("durationSeconds", 30);
			int amplifier = ctx.optInt("amplifier", 0);
			boolean hideParticles = !ctx.optBool("showParticles", true);
			String cmd = String.format("effect give %s %s %d %d %b",
					playerName(p), effect, seconds, amplifier, hideParticles);
			return CommandRunner.run(server, cmd).toJson();
		}));

		router.register("players.message", ctx -> onServer(server -> {
			Gates.commands();
			String target = ctx.getString("player");
			String text = ctx.getString("text");
			String selector;
			if (target.equalsIgnoreCase("@all") || target.equals("@a")) {
				selector = "@a";
			} else {
				selector = playerName(require(server, target));
			}
			JsonObject component = new JsonObject();
			component.addProperty("text", text);
			String cmd = "tellraw " + selector + " " + component;
			return CommandRunner.run(server, cmd).toJson();
		}));

		router.register("players.kick", ctx -> onServer(server -> {
			Gates.commands();
			ServerPlayer p = require(server, ctx.getString("player"));
			String reason = ctx.optString("reason", null);
			String cmd = "kick " + playerName(p) + (reason != null ? " " + reason : "");
			return CommandRunner.run(server, cmd).toJson();
		}));
	}

	/** The player's profile name. authlib's {@code GameProfile} became a record ({@code name()}) in 1.21.9. */
	private static String playerName(ServerPlayer p) {
		//? if <1.21.9 {
		return p.getGameProfile().getName();
		//?} else
		/*return p.getGameProfile().name();*/
	}

	private static ServerPlayer require(MinecraftServer server, String ref) throws RpcException {
		ServerPlayer p = find(server, ref);
		if (p == null) throw RpcException.notFound("No online player matching: " + ref);
		return p;
	}

	private static ServerPlayer find(MinecraftServer server, String ref) {
		try {
			UUID uuid = UUID.fromString(ref);
			return server.getPlayerList().getPlayer(uuid);
		} catch (IllegalArgumentException ignored) {
			return server.getPlayerList().getPlayerByName(ref);
		}
	}

	private static JsonObject describe(ServerPlayer p) {
		JsonObject o = new JsonObject();
		o.addProperty("name", playerName(p));
		o.addProperty("uuid", p.getUUID().toString());
		o.addProperty("x", p.getX());
		o.addProperty("y", p.getY());
		o.addProperty("z", p.getZ());
		o.addProperty("yaw", p.getYRot());
		o.addProperty("pitch", p.getXRot());
		//? if <1.21.11 {
		o.addProperty("dimension", p.level().dimension().location().toString());
		//?} else
		/*o.addProperty("dimension", p.level().dimension().identifier().toString());*/
		o.addProperty("health", p.getHealth());
		o.addProperty("maxHealth", p.getMaxHealth());
		o.addProperty("food", p.getFoodData().getFoodLevel());
		o.addProperty("gameMode", p.gameMode.getGameModeForPlayer().getName());
		o.addProperty("ping", p.connection.latency());
		o.addProperty("xpLevel", p.experienceLevel);
		return o;
	}

	private interface ServerWork {
		JsonElement run(MinecraftServer server) throws RpcException;
	}

	private static JsonElement onServer(ServerWork work) throws RpcException {
		MinecraftServer server = ServerHolder.get();
		if (server == null) throw RpcException.noServer();
		return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> work.run(server));
	}
}
