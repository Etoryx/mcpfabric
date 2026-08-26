package dev.mcpfabric.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.SseHub;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds a bounded ring buffer of recent {@link GameEvent}s (for polling via {@code events.getRecent})
 * and fans new events out to SSE subscribers.
 */
public final class EventBus {
	private static final int CAPACITY = 2000;

	private final SseHub sse;
	private final AtomicLong seq = new AtomicLong();
	private final Deque<GameEvent> ring = new ArrayDeque<>();
	private volatile long currentTick = 0L;

	public EventBus(SseHub sse) {
		this.sse = sse;
	}

	public SseHub getSseHub() {
		return sse;
	}

	/** @deprecated use {@link #getSseHub()} */
	@Deprecated
	public SseHub sse() {
		return getSseHub();
	}

	public void setTick(long tick) {
		this.currentTick = tick;
	}

	public GameEvent emit(String type, JsonObject data) {
		GameEvent event = new GameEvent(seq.incrementAndGet(), type, currentTick, data);
		synchronized (ring) {
			ring.addLast(event);
			while (ring.size() > CAPACITY) ring.removeFirst();
		}
		try {
			sse.broadcast(type, Json.GSON.toJson(event.toJson()));
		} catch (Throwable ignored) {
			// never let event delivery break the game thread
		}
		return event;
	}

	public long lastId() {
		return seq.get();
	}

	/** Return matching events (oldest first). */
	public JsonArray recent(int limit, Collection<String> typeFilter, long sinceId) {
		GameEvent[] snapshot;
		synchronized (ring) {
			snapshot = ring.toArray(new GameEvent[0]);
		}
		boolean filterTypes = typeFilter != null && !typeFilter.isEmpty();
		// Walk newest-first, collect up to `limit`, then reverse to oldest-first.
		java.util.List<JsonObject> picked = new java.util.ArrayList<>();
		for (int i = snapshot.length - 1; i >= 0 && picked.size() < limit; i--) {
			GameEvent e = snapshot[i];
			if (e.id() <= sinceId) continue;
			if (filterTypes && !typeFilter.contains(e.type())) continue;
			picked.add(e.toJson());
		}
		JsonArray out = new JsonArray();
		for (int i = picked.size() - 1; i >= 0; i--) out.add(picked.get(i));
		return out;
	}
}
