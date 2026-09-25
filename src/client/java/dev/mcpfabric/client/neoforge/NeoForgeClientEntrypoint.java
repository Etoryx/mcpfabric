package dev.mcpfabric.client.neoforge;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.client.ClientEvents;
import dev.mcpfabric.client.McpFabricClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ChatType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import java.util.UUID;

/**
 * NeoForge client entrypoint (physical client only). FML constructs entrypoints for both dists
 * first, so {@code NeoForgeEntrypoint} has already initialized the shared core.
 */
@Mod(value = McpFabric.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeClientEntrypoint {
	/** Sender UUID NeoForge reports for messages without a player sender (Util.NIL_UUID). */
	private static final UUID NO_SENDER = new UUID(0L, 0L);

	public NeoForgeClientEntrypoint() {
		McpFabricClient.init();

		NeoForge.EVENT_BUS.addListener(NeoForgeClientEntrypoint::onClientTick);
		NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, NeoForgeClientEntrypoint::onChat);
		NeoForge.EVENT_BUS.addListener(NeoForgeClientEntrypoint::onGameShuttingDown);
	}

	private static void onClientTick(ClientTickEvent.Post event) {
		McpFabricClient.onClientTick(Minecraft.getInstance());
	}

	private static void onGameShuttingDown(GameShuttingDownEvent event) {
		McpFabricClient.onClientStopping();
	}

	// ClientChatReceivedEvent.System (game / action-bar messages) is a subclass, so one listener
	// sees both kinds.
	private static void onChat(ClientChatReceivedEvent event) {
		if (event instanceof ClientChatReceivedEvent.System system) {
			ClientEvents.onSystemMessage(system.getMessage(), system.isOverlay());
		} else {
			ClientEvents.onChatMessage(event.getMessage(), senderName(event));
		}
	}

	/** Profile name of the sender (matches Fabric's GameProfile name), else the chat display name. */
	private static String senderName(ClientChatReceivedEvent event) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection != null && !NO_SENDER.equals(event.getSender())) {
			PlayerInfo info = connection.getPlayerInfo(event.getSender());
			if (info != null) {
				//? if <1.21.9 {
				return info.getProfile().getName();
				//?} else
				/*return info.getProfile().name();*/
			}
		}
		ChatType.Bound bound = event.getBoundChatType();
		return bound != null ? bound.name().getString() : null;
	}
}
