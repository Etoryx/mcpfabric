package dev.mcpfabric;

import dev.mcpfabric.bridge.HttpBridgeServer;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.config.McpConfig;
import dev.mcpfabric.events.EventBus;
import dev.mcpfabric.bridge.SseHub;
import dev.mcpfabric.handlers.CommandHandlers;
import dev.mcpfabric.handlers.EntityHandlers;
import dev.mcpfabric.handlers.GameEvents;
import dev.mcpfabric.handlers.InfoHandlers;
import dev.mcpfabric.handlers.PlayerAdminHandlers;
import dev.mcpfabric.handlers.WorldHandlers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (environment "*") entrypoint. Starts the embedded HTTP bridge and registers all
 * server-capable RPC handlers. Client-only handlers are added later from {@code McpFabricClient}
 * into the same shared {@link RpcRouter}.
 */
public class McpFabric implements ModInitializer {
	public static final String MOD_ID = "mcpfabric";
	public static final Logger LOGGER = LoggerFactory.getLogger("mcpfabric");

	public static final String MC_VERSION = FabricLoader.getInstance()
			.getModContainer("minecraft")
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	public static final String MOD_VERSION = FabricLoader.getInstance()
			.getModContainer(MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("dev");

	private static McpConfig config;
	private static RpcRouter router;
	private static EventBus eventBus;
	private static SseHub sseHub;
	private static HttpBridgeServer httpServer;

	public static McpConfig config() {
		return config;
	}

	public static RpcRouter router() {
		return router;
	}

	public static EventBus events() {
		return eventBus;
	}

	@Override
	public void onInitialize() {
		config = McpConfig.load();
		sseHub = new SseHub();
		eventBus = new EventBus(sseHub);
		router = new RpcRouter();

		// Capture the running server (dedicated or integrated).
		ServerLifecycleEvents.SERVER_STARTED.register(ServerHolder::set);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerHolder.set(null));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			try {
				eventBus.setTick(server.overworld().getGameTime());
			} catch (Throwable ignored) {
			}
		});

		// Server-capable handlers + event listeners.
		InfoHandlers.register(router);
		WorldHandlers.register(router);
		EntityHandlers.register(router);
		PlayerAdminHandlers.register(router);
		CommandHandlers.register(router);
		dev.mcpfabric.handlers.ChatHandlers.registerCommon(router, eventBus);
		GameEvents.register(eventBus);

		// On a dedicated server, chat.send broadcasts. On a client the client entrypoint registers
		// chat.send to speak as the local player, so we must not also register the server variant.
		if (FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.SERVER) {
			dev.mcpfabric.handlers.ChatHandlers.registerServerChat(router);
		}

		httpServer = new HttpBridgeServer(config, router, eventBus, sseHub);
		try {
			httpServer.start();
		} catch (Exception e) {
			LOGGER.error("[mcpfabric] failed to start HTTP bridge on {}:{}", config.host, config.port, e);
		}

		if (config.requireAuth) {
			LOGGER.info("[mcpfabric] ready — bridge http://{}:{} (token: {})", config.host, config.port,
					config.source);
		} else {
			LOGGER.warn("[mcpfabric] ready — bridge http://{}:{} (authentication disabled)", config.host,
					config.port);
		}

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			// Keep the bridge up across integrated-server restarts on the client; only stop it on a
			// dedicated server shutdown.
			if (FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.SERVER) {
				httpServer.stop();
			}
		});
	}
}
