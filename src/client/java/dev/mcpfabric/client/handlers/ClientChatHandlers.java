package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.handlers.support.Gates;
import net.minecraft.client.player.LocalPlayer;

/** Client-side {@code chat.send}: speak as the local player (a leading '/' runs a command). */
public final class ClientChatHandlers {
	private ClientChatHandlers() {}

	public static void register(RpcRouter router) {
		router.register("chat.send", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			String message = ctx.getString("message");
			boolean isCommand = message.startsWith("/");
			if (isCommand) {
				// Runs with the player's permissions, which in single player with cheats is operator level.
				Gates.commands();
				p.connection.sendCommand(message.substring(1));
			} else {
				p.connection.sendChat(message);
			}
			JsonObject o = new JsonObject();
			o.addProperty("sent", true);
			o.addProperty("asCommand", isCommand);
			return o;
		}));
	}
}
