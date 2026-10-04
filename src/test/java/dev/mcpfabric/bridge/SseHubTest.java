package dev.mcpfabric.bridge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SseHubTest {
	@Test
	void capsTheNumberOfOpenStreams() {
		SseHub hub = new SseHub();
		List<SseHub.Subscriber> open = new ArrayList<>();
		for (int i = 0; i < SseHub.MAX_SUBSCRIBERS; i++) {
			SseHub.Subscriber s = hub.register(Set.of());
			assertNotNull(s);
			open.add(s);
		}
		assertNull(hub.register(Set.of()), "each stream holds a bridge thread, so the hub refuses more");

		hub.unregister(open.get(0));
		assertNotNull(hub.register(Set.of()), "a closed stream frees its place");
		assertEquals(SseHub.MAX_SUBSCRIBERS, hub.count());
	}
}
