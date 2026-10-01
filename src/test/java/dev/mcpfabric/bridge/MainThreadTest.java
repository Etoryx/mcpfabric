package dev.mcpfabric.bridge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainThreadTest {
	@Test
	void returnsTheResultAndRethrowsRpcErrors() throws RpcException {
		assertEquals("done", MainThread.call(Runnable::run, 1000, () -> "done"));
		RpcException e = assertThrows(RpcException.class,
				() -> MainThread.call(Runnable::run, 1000, () -> { throw RpcException.badRequest("nope"); }));
		assertEquals("bad_request", e.code());
	}

	@Test
	void cancelsATaskTheGameThreadHasNotStarted() {
		// A busy game thread: the task waits in the queue past the timeout.
		List<Runnable> queue = new ArrayList<>();
		AtomicBoolean ran = new AtomicBoolean();
		RpcException e = assertThrows(RpcException.class, () -> MainThread.call(queue::add, 50, () -> {
			ran.set(true);
			return "done";
		}));
		assertEquals("timeout", e.code());
		assertTrue(e.getMessage().contains("cancelled"), e.getMessage());

		queue.forEach(Runnable::run); // the game thread gets to the queue later
		assertFalse(ran.get(), "a cancelled task must not run, or a caller that retries acts twice");
	}

	@Test
	void reportsThatAStartedTaskMayStillComplete() throws InterruptedException {
		CountDownLatch finished = new CountDownLatch(1);
		RpcException e = assertThrows(RpcException.class, () -> MainThread.call(task -> new Thread(task).start(), 50, () -> {
			sleep(300);
			finished.countDown();
			return "done";
		}));
		assertEquals("timeout", e.code());
		assertTrue(e.getMessage().contains("already started"), e.getMessage());
		assertTrue(finished.await(2, TimeUnit.SECONDS), "the started task still runs to the end");
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
