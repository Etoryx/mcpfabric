package dev.mcpfabric.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.bridge.MainThread;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import net.minecraft.server.MinecraftServer;

/**
 * {@code session.info} on a dedicated server: a stable id of the world, used by the agent runtime
 * to keep separate memory per world. The client registers its own variant (singleplayer save
 * folder or multiplayer server address).
 */
public final class SessionHandlers {
	private SessionHandlers() {}

	public static void registerServer(RpcRouter router) {
		router.register("session.info", ctx -> {
			MinecraftServer server = ServerHolder.get();
			if (server == null) throw RpcException.noServer();
			return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
				String name = server.getWorldData().getLevelName();
				JsonObject o = new JsonObject();
				o.addProperty("worldId", "server:" + name);
				o.addProperty("kind", "server");
				o.addProperty("name", name);
				return o;
			});
		});
	}
}
