package dev.mcpfabric.neoforge;

import dev.mcpfabric.platform.Platform;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

final class NeoForgePlatform implements Platform {
	private final String modVersion;
	private final boolean client;

	// The Dist comes from FML's @Mod constructor injection: stable across FML versions, unlike
	// FMLEnvironment (a field before FML 10, a method after).
	NeoForgePlatform(ModContainer container, Dist dist) {
		this.modVersion = container.getModInfo().getVersion().toString();
		this.client = dist.isClient();
	}

	@Override
	public String loader() {
		return "neoforge";
	}

	@Override
	public Path configDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	@Override
	public boolean isClient() {
		return client;
	}

	@Override
	public String minecraftVersion() {
		return ModList.get().getModContainerById("minecraft")
				.map(c -> c.getModInfo().getVersion().toString())
				.orElse("unknown");
	}

	@Override
	public String modVersion() {
		return modVersion;
	}
}
