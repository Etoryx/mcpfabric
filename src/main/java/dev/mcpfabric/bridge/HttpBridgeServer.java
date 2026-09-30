package dev.mcpfabric.bridge;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.config.McpConfig;
import dev.mcpfabric.events.EventBus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embedded HTTP bridge built on the JDK's {@link HttpServer}. Routes:
 * <ul>
 *   <li>{@code GET  /health} — liveness, no auth</li>
 *   <li>{@code GET  /info}   — status snapshot (auth)</li>
 *   <li>{@code POST /rpc}    — JSON-RPC-style dispatch (auth)</li>
 *   <li>{@code GET  /events} — Server-Sent Events stream (auth)</li>
 * </ul>
 */
public final class HttpBridgeServer {
	/** Largest request body read; RPC calls are small JSON objects, NBT strings included. */
	private static final int MAX_BODY_BYTES = 1 << 20;

	private final McpConfig cfg;
	private final RpcRouter router;
	private final EventBus events;
	private final SseHub sse;

	private HttpServer http;

	public HttpBridgeServer(McpConfig cfg, RpcRouter router, EventBus events, SseHub sse) {
		this.cfg = cfg;
		this.router = router;
		this.events = events;
		this.sse = sse;
	}

	public void start() throws IOException {
		http = HttpServer.create(new InetSocketAddress(cfg.host, cfg.port), 0);

		AtomicInteger n = new AtomicInteger();
		ThreadFactory tf = r -> {
			Thread t = new Thread(r, "mcpfabric-http-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
		http.setExecutor(Executors.newCachedThreadPool(tf));

		http.createContext("/health", this::handleHealth);
		http.createContext("/info", this::handleInfo);
		http.createContext("/rpc", this::handleRpc);
		http.createContext("/events", this::handleEvents);
		http.start();

		McpFabric.LOGGER.info("[mcpfabric] HTTP bridge listening on http://{}:{}", cfg.host, cfg.port);
	}

	public void stop() {
		if (http != null) {
			http.stop(0);
			http = null;
		}
	}

	// --- handlers ----------------------------------------------------------------------------

	private void handleHealth(HttpExchange ex) throws IOException {
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		o.addProperty("name", "mcpfabric");
		respond(ex, 200, Json.GSON.toJson(o));
	}

	private void handleInfo(HttpExchange ex) throws IOException {
		if (fromBrowser(ex) || !authorize(ex)) return;
		JsonObject env = router.dispatch("info.status", new JsonObject());
		// Return just the result for convenience; fall back to the envelope on error.
		Object result = env.has("result") ? env.get("result") : env;
		respond(ex, 200, Json.GSON.toJson(result));
	}

	private void handleRpc(HttpExchange ex) throws IOException {
		if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
			respond(ex, 405, Json.GSON.toJson(Json.envelopeError("method_not_allowed", "Use POST.", null)));
			return;
		}
		if (fromBrowser(ex) || !authorize(ex)) return;
		// A page can send a cross-site text/plain POST without a CORS preflight; JSON cannot.
		String type = ex.getRequestHeaders().getFirst("Content-Type");
		if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
			respond(ex, 415, Json.GSON.toJson(Json.envelopeError("unsupported_media_type", "Content-Type must be application/json.", null)));
			return;
		}

		String body = readBody(ex);
		if (body == null) {
			respond(ex, 413, Json.GSON.toJson(Json.envelopeError("payload_too_large",
					"Request body exceeds " + MAX_BODY_BYTES + " bytes.", null)));
			return;
		}
		JsonObject req;
		try {
			req = Json.GSON.fromJson(body, JsonObject.class);
		} catch (Exception e) {
			respond(ex, 400, Json.GSON.toJson(Json.envelopeError("bad_request", "Body must be a JSON object.", null)));
			return;
		}
		if (req == null) {
			respond(ex, 400, Json.GSON.toJson(Json.envelopeError("bad_request", "Empty body.", null)));
			return;
		}

		String method = req.has("method") && !req.get("method").isJsonNull() ? req.get("method").getAsString() : null;
		JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params") : new JsonObject();

		JsonObject env = router.dispatch(method, params);
		respond(ex, 200, Json.GSON.toJson(env));
	}

	private void handleEvents(HttpExchange ex) throws IOException {
		if (fromBrowser(ex) || !authorize(ex)) return;

		Set<String> filter = parseTypeFilter(ex.getRequestURI().getQuery());
		ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		ex.getResponseHeaders().set("Cache-Control", "no-cache");
		ex.getResponseHeaders().set("Connection", "keep-alive");
		ex.sendResponseHeaders(200, 0); // 0 => streaming, connection stays open

		SseHub.Subscriber sub = sse.register(filter);
		try (OutputStream os = ex.getResponseBody()) {
			writeSse(os, ": connected, lastEventId=" + events.lastId() + "\n\n");
			while (true) {
				String event = sub.poll(15000);
				if (event == null) {
					writeSse(os, ": keepalive\n\n"); // comment heartbeat
				} else {
					writeSse(os, "data: " + event + "\n\n");
				}
			}
		} catch (IOException | InterruptedException closed) {
			// client disconnected or thread interrupted
		} finally {
			sse.unregister(sub);
		}
	}

	// --- helpers -----------------------------------------------------------------------------

	/**
	 * Refuses requests made by web pages. The bridge serves local MCP clients, which send no Origin;
	 * a browser always sends one on a cross-site request, and binding to loopback does not keep a page
	 * the user opens from reaching it.
	 */
	private static boolean fromBrowser(HttpExchange ex) throws IOException {
		if (ex.getRequestHeaders().getFirst("Origin") == null) return false;
		respond(ex, 403, Json.GSON.toJson(Json.envelopeError("forbidden",
				"Requests from web pages (with an Origin header) are refused: the bridge is for local MCP clients.", null)));
		return true;
	}

	private boolean authorize(HttpExchange ex) throws IOException {
		if (!cfg.requireAuth || cfg.token == null || cfg.token.isBlank()) {
			return true;
		}
		String auth = ex.getRequestHeaders().getFirst("Authorization");
		String expected = "Bearer " + cfg.token;
		if (auth != null && constantTimeEquals(auth, expected)) {
			return true;
		}
		ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
		respond(ex, 401, Json.GSON.toJson(Json.envelopeError("unauthorized", "Missing or invalid bearer token.", null)));
		return false;
	}

	private static boolean constantTimeEquals(String a, String b) {
		byte[] x = a.getBytes(StandardCharsets.UTF_8);
		byte[] y = b.getBytes(StandardCharsets.UTF_8);
		if (x.length != y.length) return false;
		int r = 0;
		for (int i = 0; i < x.length; i++) r |= x[i] ^ y[i];
		return r == 0;
	}

	private static Set<String> parseTypeFilter(String query) {
		Set<String> filter = new HashSet<>();
		if (query != null) {
			for (String part : query.split("&")) {
				int eq = part.indexOf('=');
				if (eq > 0 && part.substring(0, eq).equals("types")) {
					for (String t : part.substring(eq + 1).split(",")) {
						if (!t.isBlank()) filter.add(t.trim());
					}
				}
			}
		}
		return filter;
	}

	/** The request body as text, or null when it is larger than {@link #MAX_BODY_BYTES}. */
	private static String readBody(HttpExchange ex) throws IOException {
		String declared = ex.getRequestHeaders().getFirst("Content-Length");
		try {
			if (declared != null && Long.parseLong(declared.trim()) > MAX_BODY_BYTES) return null;
		} catch (NumberFormatException ignored) {
			// no usable length: the read below still stops at the limit
		}
		try (InputStream is = ex.getRequestBody()) {
			byte[] bytes = is.readNBytes(MAX_BODY_BYTES + 1);
			return bytes.length > MAX_BODY_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
		}
	}

	private static void respond(HttpExchange ex, int code, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(code, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	private static void writeSse(OutputStream os, String s) throws IOException {
		os.write(s.getBytes(StandardCharsets.UTF_8));
		os.flush();
	}
}
