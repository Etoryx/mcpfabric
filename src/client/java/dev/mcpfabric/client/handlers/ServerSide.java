package dev.mcpfabric.client.handlers;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.bridge.MainThread;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.ThrowingSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * The integrated server's side of a client action. Client-side interactions only predict their
 * result (the server may refuse them), so break/place confirm what the server actually did whenever
 * the game runs its own server. Connected to a remote server, there is nothing to read.
 */
final class ServerSide {
	private ServerSide() {}

	/** The integrated server's level for a client dimension, or null when connected to a remote server. */
	@Nullable
	static ServerLevel level(ResourceKey<Level> dimension) {
		MinecraftServer server = ServerHolder.get();
		return server == null ? null : server.getLevel(dimension);
	}

	/** Runs {@code task} on the server thread. */
	static <T> T call(ServerLevel level, ThrowingSupplier<T> task) throws RpcException {
		return MainThread.call(level.getServer(), McpFabric.config().callTimeoutMs, task);
	}

	/** Waits up to {@code waitMs} for the server's block at {@code pos} to satisfy {@code done}; returns the last state read. */
	static BlockState await(ServerLevel level, BlockPos pos, Predicate<BlockState> done, long waitMs) throws RpcException {
		long deadline = System.nanoTime() + waitMs * 1_000_000L;
		while (true) {
			BlockState state = call(level, () -> level.getBlockState(pos));
			if (done.test(state) || System.nanoTime() >= deadline) return state;
			try {
				Thread.sleep(25);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RpcException("interrupted", "Interrupted while waiting for the server.");
			}
		}
	}
}
