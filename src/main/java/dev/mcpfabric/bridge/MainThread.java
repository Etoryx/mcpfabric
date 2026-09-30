package dev.mcpfabric.bridge;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridges HTTP worker threads onto the Minecraft main thread.
 *
 * <p>All access to world/entity/player state must happen on the game thread. Both
 * {@code MinecraftServer} and {@code Minecraft} are {@link Executor}s, so handlers schedule work
 * via these helpers and block the (cheap) HTTP worker thread until the result is ready.
 */
public final class MainThread {
	private static final int PENDING = 0;
	private static final int STARTED = 1;
	private static final int CANCELLED = 2;

	private MainThread() {}

	/**
	 * Runs {@code task} on the game thread and waits up to {@code timeoutMs} for its result.
	 *
	 * <p>On timeout, a task the game thread has not picked up yet is cancelled and never runs, so a
	 * caller that retries does not act twice. A task that already started cannot be stopped: the
	 * error then says it may still complete.
	 */
	public static <T> T call(Executor gameThread, long timeoutMs, ThrowingSupplier<T> task) throws RpcException {
		CompletableFuture<T> future = new CompletableFuture<>();
		AtomicInteger state = new AtomicInteger(PENDING);
		gameThread.execute(() -> {
			if (!state.compareAndSet(PENDING, STARTED)) return;
			try {
				future.complete(task.get());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		try {
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			if (state.compareAndSet(PENDING, CANCELLED)) {
				throw new RpcException("timeout", "The game thread did not start the action within " + timeoutMs
						+ "ms. It was cancelled and will not run.");
			}
			throw new RpcException("timeout", "The game thread did not finish the action within " + timeoutMs
					+ "ms. It already started and may still complete: check the game state before retrying.");
		} catch (InterruptedException e) {
			state.compareAndSet(PENDING, CANCELLED);
			Thread.currentThread().interrupt();
			throw new RpcException("interrupted", "Interrupted while waiting for the game thread.");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			if (cause instanceof RpcException rpc) {
				throw rpc;
			}
			throw new RpcException("internal", cause.getClass().getSimpleName() + ": " + cause.getMessage());
		}
	}

	/** Schedule work without waiting for a result. */
	public static void run(Executor gameThread, Runnable task) {
		gameThread.execute(task);
	}
}
