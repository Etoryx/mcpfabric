package dev.mcpfabric.platform;

import java.nio.file.Path;

/**
 * The few mod-loader services the shared code needs. Each loader entrypoint (Fabric, NeoForge)
 * supplies its own implementation to {@link dev.mcpfabric.McpFabric#init(Platform)}; nothing outside
 * the loader packages may touch loader APIs directly.
 */
public interface Platform {
	/** Loader name as reported by {@code info.status} ({@code "fabric"} / {@code "neoforge"}). */
	String loader();

	/** Directory holding {@code mcpfabric.config.json}. */
	Path configDir();

	/** True on the physical client (singleplayer / connected client), false on a dedicated server. */
	boolean isClient();

	String minecraftVersion();

	String modVersion();
}
