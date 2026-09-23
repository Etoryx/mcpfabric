package dev.mcpfabric.neoforge;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.handlers.GameEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge common entrypoint: wires NeoForge game events to the shared core. FML constructs it
 * before the client-only {@code NeoForgeClientEntrypoint}. Observers run at
 * {@link EventPriority#LOWEST} and skip cancelled events, so they see what actually happened, like
 * Fabric's post-action callbacks.
 */
@Mod(McpFabric.MOD_ID)
public final class NeoForgeEntrypoint {
	public NeoForgeEntrypoint(IEventBus modBus, ModContainer container, Dist dist) {
		McpFabric.init(new NeoForgePlatform(container, dist));

		IEventBus bus = NeoForge.EVENT_BUS;
		bus.addListener(NeoForgeEntrypoint::onServerStarted);
		bus.addListener(NeoForgeEntrypoint::onServerStopping);
		bus.addListener(NeoForgeEntrypoint::onServerStopped);
		bus.addListener(NeoForgeEntrypoint::onServerTick);
		bus.addListener(EventPriority.LOWEST, NeoForgeEntrypoint::onChat);
		bus.addListener(NeoForgeEntrypoint::onPlayerJoin);
		bus.addListener(NeoForgeEntrypoint::onPlayerLeave);
		bus.addListener(EventPriority.LOWEST, NeoForgeEntrypoint::onDeath);
		bus.addListener(EventPriority.LOWEST, NeoForgeEntrypoint::onDamage);
	}

	private static void onServerStarted(ServerStartedEvent event) {
		McpFabric.onServerStarted(event.getServer());
	}

	private static void onServerStopping(ServerStoppingEvent event) {
		McpFabric.onServerStopping(event.getServer());
	}

	private static void onServerStopped(ServerStoppedEvent event) {
		McpFabric.onServerStopped(event.getServer());
	}

	private static void onServerTick(ServerTickEvent.Post event) {
		McpFabric.onServerTick(event.getServer());
	}

	private static void onChat(ServerChatEvent event) {
		GameEvents.onChat(event.getPlayer(), event.getRawText());
	}

	private static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
		GameEvents.onPlayerJoin(event.getEntity());
	}

	private static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
		GameEvents.onPlayerLeave(event.getEntity());
	}

	private static void onDeath(LivingDeathEvent event) {
		if (!event.getEntity().level().isClientSide()) {
			GameEvents.onDeath(event.getEntity(), event.getSource());
		}
	}

	private static void onDamage(LivingIncomingDamageEvent event) {
		if (!event.getEntity().level().isClientSide()) {
			GameEvents.onDamage(event.getEntity(), event.getSource(), event.getAmount());
		}
	}
}
