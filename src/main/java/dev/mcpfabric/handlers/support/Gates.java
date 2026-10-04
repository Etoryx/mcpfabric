package dev.mcpfabric.handlers.support;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;

/**
 * The capability locks of {@code mcpfabric.config.json}. Every handler that belongs to a gated
 * group checks its lock, so what {@code get_status} reports as disabled really is unavailable.
 */
public final class Gates {
	private Gates() {}

	/** Movement, looking, item use, inventory changes and navigation of the local player. */
	public static void playerControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}

	/** Screenshots and scene descriptions. */
	public static void vision() throws RpcException {
		if (!McpFabric.config().enableVision) {
			throw RpcException.unavailable("Vision is disabled in mcpfabric.config.json (enableVision=false).");
		}
	}

	/** Anything that runs a server command, including the player admin tools (they run level-4 commands). */
	public static void commands() throws RpcException {
		if (!McpFabric.config().enableCommands) {
			throw RpcException.unavailable("Commands are disabled in mcpfabric.config.json (enableCommands=false).");
		}
	}

	/**
	 * Block or entity data (NBT) can create a command block or command minecart that runs any command,
	 * so it is only accepted while commands are enabled.
	 */
	public static void dataTags(String data) throws RpcException {
		if (data != null && data.indexOf('{') >= 0 && !McpFabric.config().enableCommands) {
			throw RpcException.unavailable("Block and entity data ({...}) can run commands, and commands are disabled "
					+ "in mcpfabric.config.json (enableCommands=false).");
		}
	}
}
