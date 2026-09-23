package dev.mcpfabric.fabric;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.handlers.GameEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** Fabric common (environment "*") entrypoint: wires Fabric API events to the shared core. */
public class FabricEntrypoint implements ModInitializer {
	@Override
	public void onInitialize() {
		McpFabric.init(new FabricPlatform());

		ServerLifecycleEvents.SERVER_STARTED.register(McpFabric::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(McpFabric::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(McpFabric::onServerStopped);
		ServerTickEvents.END_SERVER_TICK.register(McpFabric::onServerTick);

		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
				GameEvents.onChat(sender, message.signedContent()));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				GameEvents.onPlayerJoin(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				GameEvents.onPlayerLeave(handler.player));
		ServerLivingEntityEvents.AFTER_DEATH.register(GameEvents::onDeath);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			GameEvents.onDamage(entity, source, amount);
			return true; // never block damage; we only observe
		});
	}
}
