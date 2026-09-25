package dev.mcpfabric.client.fabric;

import dev.mcpfabric.client.ClientEvents;
import dev.mcpfabric.client.McpFabricClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/** Fabric client entrypoint: runs after {@code FabricEntrypoint} and wires client events. */
public class FabricClientEntrypoint implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		McpFabricClient.init();

		ClientTickEvents.END_CLIENT_TICK.register(McpFabricClient::onClientTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> McpFabricClient.onClientStopping());
		ClientReceiveMessageEvents.GAME.register(ClientEvents::onSystemMessage);
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			String name = null;
			if (sender != null) {
				//? if <1.21.9 {
				name = sender.getName();
				//?} else
				/*name = sender.name();*/
			}
			ClientEvents.onChatMessage(message, name);
		});
	}
}
