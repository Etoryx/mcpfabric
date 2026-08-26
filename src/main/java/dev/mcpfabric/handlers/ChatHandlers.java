package dev.mcpfabric.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.bridge.MainThread;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.events.EventBus;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/**
 * Chat and interrupt-driven event waiting handlers.
 */
public final class ChatHandlers {
	private ChatHandlers() {}

	public static void registerCommon(RpcRouter router, EventBus events) {
		router.register("chat.getRecent", ctx -> {
			int limit = ctx.optInt("limit", 50);
			JsonObject o = new JsonObject();
			o.add("messages", events.recent(limit, List.of("chat", "system_message"), 0));
			return o;
		});

		router.register("events.getRecent", ctx -> {
			int limit = ctx.optInt("limit", 50);
			long sinceId = ctx.optLong("sinceId", 0);
			JsonObject o = new JsonObject();
			o.add("events", events.recent(limit, ctx.getStringList("types"), sinceId));
			o.addProperty("lastId", events.lastId());
			return o;
		});

		router.register("chat.waitFor", ctx -> {
			long timeoutMs = resolveTimeoutMs(ctx);
			long sinceId = ctx.optLong("sinceId", events.lastId());
			return waitForEvents(events, java.util.Set.of("chat", "system_message"), sinceId, timeoutMs);
		});

		router.register("events.waitFor", ctx -> {
			long timeoutMs = resolveTimeoutMs(ctx);
			long sinceId = ctx.optLong("sinceId", events.lastId());
			java.util.List<String> typesList = ctx.getStringList("types");
			java.util.Set<String> types = !typesList.isEmpty() ? new java.util.HashSet<>(typesList) : null;
			return waitForEvents(events, types, sinceId, timeoutMs);
		});
	}

	private static long resolveTimeoutMs(dev.mcpfabric.bridge.RpcRouter.RpcContext ctx) {
		// Accept both timeoutMs (direct) and timeoutSeconds (from MCP tools.ts schema)
		long rawMs;
		if (ctx.has("timeoutMs")) {
			rawMs = ctx.optLong("timeoutMs", 30000L);
		} else if (ctx.has("timeoutSeconds")) {
			rawMs = ctx.optLong("timeoutSeconds", 30L) * 1000L;
		} else {
			rawMs = 30000L;
		}
		return Math.min(Math.max(rawMs, 1000L), 120000L);
	}

	private static JsonObject waitForEvents(EventBus events, java.util.Set<String> types, long sinceId, long timeoutMs) {
		com.google.gson.JsonArray existing = events.recent(1, types, sinceId);
		if (!existing.isEmpty()) {
			JsonObject o = new JsonObject();
			o.addProperty("received", true);
			o.add("event", existing.get(0));
			return o;
		}

		dev.mcpfabric.bridge.SseHub.Subscriber sub = events.getSseHub().register(types);
		try {
			long deadline = System.currentTimeMillis() + timeoutMs;
			while (System.currentTimeMillis() < deadline) {
				long remaining = Math.max(1L, deadline - System.currentTimeMillis());
				String raw = sub.poll(remaining);
				if (raw != null) {
					JsonObject eventObj = dev.mcpfabric.bridge.Json.GSON.fromJson(raw, JsonObject.class);
					long id = eventObj.has("id") ? eventObj.get("id").getAsLong() : 0L;
					if (id > sinceId) {
						JsonObject o = new JsonObject();
						o.addProperty("received", true);
						o.add("event", eventObj);
						return o;
					}
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			events.getSseHub().unregister(sub);
		}

		JsonObject o = new JsonObject();
		o.addProperty("received", false);
		o.addProperty("timeout", true);
		o.addProperty("lastId", events.lastId());
		return o;
	}

	public static void registerServerChat(RpcRouter router) {
		router.register("chat.send", ctx -> {
			MinecraftServer server = ServerHolder.get();
			if (server == null) throw RpcException.noServer();
			String message = ctx.getString("message");
			return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
				server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
				JsonObject o = new JsonObject();
				o.addProperty("sent", true);
				o.addProperty("broadcast", true);
				return o;
			});
		});
	}
}
