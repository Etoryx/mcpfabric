package dev.mcpfabric.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Server-side game events that feed the {@link dev.mcpfabric.events.EventBus} (polled via
 * {@code events.getRecent} and streamed over SSE). The loader entrypoint subscribes to its own
 * events and forwards them here, so the payloads are identical on every loader.
 */
public final class GameEvents {
	private GameEvents() {}

	/** A player's chat message was accepted by the server. */
	public static void onChat(Player sender, String text) {
		JsonObject d = new JsonObject();
		d.addProperty("player", sender.getName().getString());
		d.addProperty("uuid", sender.getUUID().toString());
		d.addProperty("text", text);
		McpFabric.events().emit("chat", d);
	}

	public static void onPlayerJoin(Player player) {
		McpFabric.events().emit("player_join", playerInfo(player));
	}

	public static void onPlayerLeave(Player player) {
		McpFabric.events().emit("player_leave", playerInfo(player));
	}

	public static void onDeath(LivingEntity entity, DamageSource damageSource) {
		JsonObject d = new JsonObject();
		d.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
		d.addProperty("uuid", entity.getUUID().toString());
		d.addProperty("name", entity.getName().getString());
		d.addProperty("isPlayer", entity instanceof Player);
		d.addProperty("cause", damageSource.getMsgId());
		d.addProperty("x", entity.getX());
		d.addProperty("y", entity.getY());
		d.addProperty("z", entity.getZ());
		McpFabric.events().emit(entity instanceof Player ? "player_death" : "entity_death", d);
	}

	/** An entity is about to take damage. Observe-only: callers must never block the damage. */
	public static void onDamage(LivingEntity entity, DamageSource source, float amount) {
		if (entity instanceof Player) {
			JsonObject d = new JsonObject();
			d.addProperty("player", entity.getName().getString());
			d.addProperty("uuid", entity.getUUID().toString());
			d.addProperty("amount", amount);
			d.addProperty("cause", source.getMsgId());
			d.addProperty("healthBefore", entity.getHealth());
			McpFabric.events().emit("player_damage", d);
		}
	}

	private static JsonObject playerInfo(Player player) {
		JsonObject d = new JsonObject();
		d.addProperty("player", player.getName().getString());
		d.addProperty("uuid", player.getUUID().toString());
		return d;
	}
}
