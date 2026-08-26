package dev.mcpfabric.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.events.EventBus;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Server-side event listeners that feed the {@link EventBus} (polled via {@code events.getRecent}
 * and streamed over SSE).
 */
public final class GameEvents {
	private GameEvents() {}

	public static void register(EventBus events) {
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			JsonObject d = new JsonObject();
			d.addProperty("player", sender.getName().getString());
			d.addProperty("uuid", sender.getUUID().toString());
			d.addProperty("text", message.signedContent());
			events.emit("chat", d);
		});

		ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) -> {
			JsonObject d = new JsonObject();
			d.addProperty("text", message.signedContent());
			if (source.isPlayer() && source.getPlayer() != null) {
				d.addProperty("player", source.getPlayer().getName().getString());
				d.addProperty("uuid", source.getPlayer().getUUID().toString());
			} else {
				d.addProperty("player", source.getTextName());
			}
			events.emit("command_message", d);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			JsonObject d = new JsonObject();
			d.addProperty("player", handler.player.getName().getString());
			d.addProperty("uuid", handler.player.getUUID().toString());
			events.emit("player_join", d);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			JsonObject d = new JsonObject();
			d.addProperty("player", handler.player.getName().getString());
			d.addProperty("uuid", handler.player.getUUID().toString());
			events.emit("player_leave", d);
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			JsonObject d = new JsonObject();
			d.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
			d.addProperty("uuid", entity.getUUID().toString());
			d.addProperty("name", entity.getName().getString());
			d.addProperty("isPlayer", entity instanceof Player);
			d.addProperty("cause", damageSource.getMsgId());
			d.addProperty("x", entity.getX());
			d.addProperty("y", entity.getY());
			d.addProperty("z", entity.getZ());
			events.emit(entity instanceof Player ? "player_death" : "entity_death", d);
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((LivingEntity entity, net.minecraft.world.damagesource.DamageSource source, float amount) -> {
			if (entity instanceof Player) {
				JsonObject d = new JsonObject();
				d.addProperty("player", entity.getName().getString());
				d.addProperty("uuid", entity.getUUID().toString());
				d.addProperty("amount", amount);
				d.addProperty("cause", source.getMsgId());
				d.addProperty("healthBefore", entity.getHealth());
				events.emit("player_damage", d);
			}
			return true; // never block damage; we only observe
		});
	}
}
