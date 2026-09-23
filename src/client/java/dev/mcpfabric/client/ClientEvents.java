package dev.mcpfabric.client;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import net.minecraft.network.chat.Component;

/**
 * Client-side events that feed the shared {@link dev.mcpfabric.events.EventBus}. The loader client
 * entrypoint forwards its own chat events here.
 */
public final class ClientEvents {
	private ClientEvents() {}

	/** A system / game message was shown (overlay = action bar). */
	public static void onSystemMessage(Component message, boolean overlay) {
		JsonObject d = new JsonObject();
		d.addProperty("text", message.getString());
		d.addProperty("overlay", overlay);
		McpFabric.events().emit("system_message", d);
	}

	/** A player chat message was received; {@code sender} is null when unknown. */
	public static void onChatMessage(Component message, String sender) {
		JsonObject d = new JsonObject();
		d.addProperty("text", message.getString());
		if (sender != null) {
			d.addProperty("sender", sender);
		}
		McpFabric.events().emit("chat", d);
	}
}
