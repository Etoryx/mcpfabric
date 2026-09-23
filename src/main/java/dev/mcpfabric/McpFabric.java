package dev.mcpfabric;

import dev.mcpfabric.bridge.HttpBridgeServer;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.config.McpConfig;
import dev.mcpfabric.events.EventBus;
import dev.mcpfabric.bridge.SseHub;
import dev.mcpfabric.handlers.CommandHandlers;
import dev.mcpfabric.handlers.EntityHandlers;
import dev.mcpfabric.handlers.InfoHandlers;
import dev.mcpfabric.handlers.PlayerAdminHandlers;
import dev.mcpfabric.handlers.WorldHandlers;
import dev.mcpfabric.platform.Platform;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-independent core. The loader entrypoint ({@code dev.mcpfabric.fabric} /
 * {@code dev.mcpfabric.neoforge}) calls {@link #init(Platform)} once, then forwards server lifecycle
 * and tick events to the {@code on*} hooks below. Starts the embedded HTTP bridge and registers all
 * server-capable RPC handlers; client-only handlers are added later from {@code McpFabricClient}
 * into the same shared {@link RpcRouter}.
 */
public final class McpFabric {
	public static final String MOD_ID = "mcpfabric";
	public static final Logger LOGGER = LoggerFactory.getLogger("mcpfabric");

	private static Platform platform;
	private static McpConfig config;
	private static RpcRouter router;
	private static EventBus eventBus;
	private static SseHub sseHub;
	private static HttpBridgeServer httpServer;

	private McpFabric() {}

	public static Platform platform() {
		return platform;
	}

	public static McpConfig config() {
		return config;
	}

	public static RpcRouter router() {
		return router;
	}

	public static EventBus events() {
		return eventBus;
	}

	public static void init(Platform loaderPlatform) {
		platform = loaderPlatform;
		config = McpConfig.load();
		sseHub = new SseHub();
		eventBus = new EventBus(sseHub);
		router = new RpcRouter();

		// Server-capable handlers. Game event listeners are wired by the loader entrypoint and feed
		// the bus through GameEvents.
		InfoHandlers.register(router);
		WorldHandlers.register(router);
		EntityHandlers.register(router);
		PlayerAdminHandlers.register(router);
		CommandHandlers.register(router);
		dev.mcpfabric.handlers.ChatHandlers.registerCommon(router, eventBus);

		// On a dedicated server, chat.send broadcasts. On a client the client entrypoint registers
		// chat.send to speak as the local player, so we must not also register the server variant.
		if (!platform.isClient()) {
			dev.mcpfabric.handlers.ChatHandlers.registerServerChat(router);
		}

		httpServer = new HttpBridgeServer(config, router, eventBus, sseHub);
		try {
			httpServer.start();
		} catch (Exception e) {
			LOGGER.error("[mcpfabric] failed to start HTTP bridge on {}:{}", config.host, config.port, e);
		}

		if (config.requireAuth) {
			LOGGER.info("[mcpfabric] ready ({}) — bridge http://{}:{} (token: {})", platform.loader(),
					config.host, config.port, config.source);
		} else {
			LOGGER.warn("[mcpfabric] ready ({}) — bridge http://{}:{} (authentication disabled)",
					platform.loader(), config.host, config.port);
		}
	}

	/** The running server (dedicated or integrated) finished starting. */
	public static void onServerStarted(MinecraftServer server) {
		ServerHolder.set(server);
	}

	public static void onServerStopping(MinecraftServer server) {
		// Keep the bridge up across integrated-server restarts on the client; only stop it on a
		// dedicated server shutdown.
		if (!platform.isClient()) {
			httpServer.stop();
		}
	}

	public static void onServerStopped(MinecraftServer server) {
		ServerHolder.set(null);
	}

	/** End of every server tick. */
	public static void onServerTick(MinecraftServer server) {
		try {
			eventBus.setTick(server.overworld().getGameTime());
		} catch (Throwable ignored) {
		}
	}
}
