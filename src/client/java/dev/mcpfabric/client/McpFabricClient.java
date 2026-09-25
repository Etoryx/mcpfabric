package dev.mcpfabric.client;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.handlers.ClientChatHandlers;
import dev.mcpfabric.client.handlers.ControlHandlers;
import dev.mcpfabric.client.handlers.InteractHandlers;
import dev.mcpfabric.client.handlers.InventoryHandlers;
import dev.mcpfabric.client.handlers.LocalPlayerHandlers;
import dev.mcpfabric.client.handlers.NavHandlers;
import dev.mcpfabric.client.handlers.VisionHandlers;
import net.minecraft.client.Minecraft;

/**
 * Loader-independent client core. The loader client entrypoint calls {@link #init()} after
 * {@link McpFabric#init}, then forwards client ticks to {@link #onClientTick} and chat to
 * {@link ClientEvents}.
 */
public final class McpFabricClient {
	private McpFabricClient() {}

	/** Registers all client-only handlers into the shared router started by {@link McpFabric}. */
	public static void init() {
		RpcRouter router = McpFabric.router();
		if (router == null) {
			McpFabric.LOGGER.error("[mcpfabric] router not initialized; client handlers unavailable");
			return;
		}

		LocalPlayerHandlers.register(router);
		ControlHandlers.register(router);
		InteractHandlers.register(router);
		InventoryHandlers.register(router);
		VisionHandlers.register(router);
		NavHandlers.register(router);
		ClientChatHandlers.register(router); // client variant of chat.send (speaks as local player)

		McpFabric.LOGGER.info("[mcpfabric] client handlers registered");
	}

	/** End of every client tick: drives the {@link BotController}. */
	public static void onClientTick(Minecraft client) {
		BotController.get().onClientTick(client);
	}

	/** The game is closing: stop the bridge so the JVM can exit (issue #24). */
	public static void onClientStopping() {
		McpFabric.stopBridge();
	}
}
