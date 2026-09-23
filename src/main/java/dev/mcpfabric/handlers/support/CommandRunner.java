package dev.mcpfabric.handlers.support;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes server commands at permission level 4 while capturing their chat/system output.
 *
 * <p>Many "write" operations (setblock, summon, give, teleport, effect, weather, time, ...) are
 * implemented as command invocations: this avoids fragile programmatic parsing of block/item/NBT
 * strings and gives the exact same behaviour an operator would get from the console.
 */
public final class CommandRunner {
	private CommandRunner() {}

	public static Result run(MinecraftServer server, String command) {
		return run(server, server.overworld(), command);
	}

	public static Result run(MinecraftServer server, ServerLevel level, String command) {
		Capture capture = new Capture();
		boolean[] success = {false};
		int[] resultValue = {0};
		CommandResultCallback callback = (ok, value) -> {
			success[0] = ok;
			resultValue[0] = value;
		};
		CommandSourceStack source = new CommandSourceStack(
				capture,
				Vec3.atLowerCornerOf(Levels.spawnPos(level)),
				Vec2.ZERO,
				level,
				//? if <1.21.11 {
				4,
				//?} else
				/*net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER,*/
				// 26.3 dropped the separate text name and entity arguments from this constructor.
				//? if <26.3 {
				"mcpfabric",
				Component.literal("mcpfabric"),
				server,
				null).withCallback(callback);
				//?} else {
				/*Component.literal("mcpfabric"),
				server).withCallback(callback);
				*///?}
		server.getCommands().performPrefixedCommand(source, command);
		return new Result(command, success[0], resultValue[0], capture.messages);
	}

	public record Result(String command, boolean success, int resultValue, List<String> output) {
		public JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("command", command);
			o.addProperty("success", success);
			o.addProperty("resultValue", resultValue);
			JsonArray out = new JsonArray();
			for (String line : output) out.add(line);
			o.add("output", out);
			return o;
		}
	}

	/** A {@link CommandSource} that records all feedback as plain strings. */
	private static final class Capture implements CommandSource {
		private final List<String> messages = new ArrayList<>();

		@Override
		public void sendSystemMessage(Component message) {
			messages.add(message.getString());
		}

		@Override
		public boolean acceptsSuccess() {
			return true;
		}

		@Override
		public boolean acceptsFailure() {
			return true;
		}

		@Override
		public boolean shouldInformAdmins() {
			return false;
		}
	}
}
