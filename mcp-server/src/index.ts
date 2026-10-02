#!/usr/bin/env node
/**
 * mcpfabric MCP server entrypoint.
 *
 * Registers every tool from the catalogue (`tools.ts`) as a thin forwarder to the in-game HTTP
 * bridge, then serves them over stdio (default) or streamable HTTP. All diagnostic logging goes to
 * stderr so it never corrupts the stdio JSON-RPC stream.
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import http from "node:http";
import { randomUUID } from "node:crypto";
import { createRequire } from "node:module";

import { loadConfig, type ServerConfig } from "./config.js";
import { BridgeClient, BridgeError, BridgeUnreachableError } from "./bridge.js";
import { TOOLS, type ToolDef } from "./tools.js";
import { isLocalRequest } from "./local-request.js";
import { AgentRuntime, defaultDbPath } from "./agent/runtime.js";
import { AGENT_TOOLS, AgentSession } from "./agent/tools.js";

// dist/index.js -> ../package.json, so the reported version always matches the published package.
const PKG_VERSION = (createRequire(import.meta.url)("../package.json") as { version: string }).version;

/** HTTP transport: sessions idle this long are closed, and at most MAX_HTTP_SESSIONS stay open. */
const SESSION_IDLE_MS = 30 * 60 * 1000;
const MAX_HTTP_SESSIONS = 32;

function log(...args: unknown[]): void {
  // stderr only — stdout is reserved for the stdio transport.
  console.error("[mcpfabric]", ...args);
}

function errorResult(err: unknown): CallToolResult {
  let text: string;
  if (err instanceof BridgeUnreachableError) {
    text = `Bridge unreachable: ${err.message}`;
  } else if (err instanceof BridgeError) {
    text = `Bridge error [${err.code}]: ${err.message}`;
    if (err.data !== undefined) text += `\n${JSON.stringify(err.data)}`;
  } else if (err instanceof Error) {
    text = `Error: ${err.message}`;
  } else {
    text = `Error: ${String(err)}`;
  }
  return { isError: true, content: [{ type: "text", text }] };
}

function jsonResult(result: unknown): CallToolResult {
  if (result === undefined || result === null) {
    return { content: [{ type: "text", text: "ok" }] };
  }
  const text = typeof result === "string" ? result : JSON.stringify(result, null, 2);
  const out: CallToolResult = { content: [{ type: "text", text }] };
  if (typeof result === "object") {
    out.structuredContent = result as Record<string, unknown>;
  }
  return out;
}

interface ScreenshotResult {
  format?: string;
  base64: string;
  width?: number;
  height?: number;
}

function imageResult(result: unknown): CallToolResult {
  const r = result as ScreenshotResult;
  if (!r || typeof r.base64 !== "string") {
    return errorResult(new Error("Bridge did not return image data."));
  }
  const mimeType = r.format === "jpeg" || r.format === "jpg" ? "image/jpeg" : "image/png";
  const meta = `Screenshot ${r.width ?? "?"}x${r.height ?? "?"} (${mimeType})`;
  return {
    content: [
      { type: "image", data: r.base64, mimeType },
      { type: "text", text: meta },
    ],
  };
}

function registerTools(server: McpServer, bridge: BridgeClient): void {
  for (const def of TOOLS as ToolDef[]) {
    const config = {
      title: def.title,
      description: def.description,
      inputSchema: def.inputSchema,
      ...(def.annotations ? { annotations: def.annotations } : {}),
    };

    const handler = async (args: Record<string, unknown>): Promise<CallToolResult> => {
      try {
        const result = await bridge.call(def.method, args ?? {});
        return def.kind === "image" ? imageResult(result) : jsonResult(result);
      } catch (err) {
        return errorResult(err);
      }
    };

    server.registerTool(def.name, config, handler);
  }
}

function registerAgentTools(server: McpServer, runtime: AgentRuntime): void {
  const session = new AgentSession();
  // A job must not keep moving the player after the session that started it is gone.
  server.server.onclose = () => {
    void runtime.jobs.cancelOwnedBy(session.id).catch(() => undefined);
  };
  for (const def of AGENT_TOOLS) {
    const config = {
      title: def.title,
      description: def.description,
      inputSchema: def.inputSchema,
      ...(def.annotations ? { annotations: def.annotations } : {}),
    };
    const handler = async (args: Record<string, unknown>): Promise<CallToolResult> => {
      try {
        const text = await def.run(runtime, session, args ?? {});
        return { content: [{ type: "text", text }] };
      } catch (err) {
        return errorResult(err);
      }
    };
    server.registerTool(def.name, config, handler);
  }
}

const BASE_INSTRUCTIONS =
  "Control and observe a running Minecraft: Java Edition game (1.21.1 or newer, Fabric or NeoForge) through the mcpfabric mod. " +
  "Call get_status first to learn which side you are on and which capability groups are available. " +
  "Client-side tools (get_self, set_movement, look, break_block, place_block, use_item, screenshot, describe_scene, navigate_to, ...) drive the local player; " +
  "server-side tools (list_players, teleport_player, run_command, set_block, fill_blocks, query_entities, ...) require an integrated or dedicated server.";

const AGENT_INSTRUCTIONS =
  " Agent runtime: memory, goals and the world map persist per world across sessions. Start with " +
  "agent_brief; keep a goal tree (goal_add/goal_update); observe after moving; remember what matters " +
  "and recall before searching the world again; use plan_craft before gathering. Multi-step actions " +
  "(travel_to, explore, collect_blocks, craft_item) run as background jobs — follow them with job_status " +
  "and do not drive movement manually while one runs.";

function buildServer(bridge: BridgeClient, runtime: AgentRuntime | undefined): McpServer {
  const server = new McpServer(
    { name: "mcpfabric", version: PKG_VERSION },
    { instructions: BASE_INSTRUCTIONS + (runtime ? AGENT_INSTRUCTIONS : "") },
  );
  registerTools(server, bridge);
  if (runtime) registerAgentTools(server, runtime);
  return server;
}

async function runStdio(bridge: BridgeClient, runtime: AgentRuntime | undefined): Promise<void> {
  const server = buildServer(bridge, runtime);
  const transport = new StdioServerTransport();
  await server.connect(transport);
  log(`stdio transport ready (bridge: ${process.env.MCPFABRIC_URL ?? "http://127.0.0.1:25599"})`);
}

async function runHttp(bridge: BridgeClient, runtime: AgentRuntime | undefined, cfg: ServerConfig): Promise<void> {
  // Stateful streamable-HTTP: one transport+server per session id.
  const sessions = new Map<string, { server: McpServer; transport: StreamableHTTPServerTransport; lastSeen: number }>();

  // Close sessions whose client went away without DELETE; otherwise they would stay open forever.
  const sweep = setInterval(() => {
    const now = Date.now();
    for (const [id, entry] of sessions) {
      if (now - entry.lastSeen > SESSION_IDLE_MS) {
        sessions.delete(id);
        void entry.transport.close();
      }
    }
  }, 60_000);
  sweep.unref();

  async function readBody(req: http.IncomingMessage): Promise<unknown> {
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk as Buffer);
    if (chunks.length === 0) return undefined;
    try {
      return JSON.parse(Buffer.concat(chunks).toString("utf8"));
    } catch {
      return undefined;
    }
  }

  const httpServer = http.createServer(async (req, res) => {
    if (!req.url || !req.url.startsWith("/mcp")) {
      res.writeHead(404).end("Not found");
      return;
    }
    if (!isLocalRequest(req.headers, cfg.httpPort)) {
      res.writeHead(403, { "Content-Type": "text/plain" }).end("Forbidden: only local clients may use this server.");
      return;
    }
    const sessionId = req.headers["mcp-session-id"];
    const sid = Array.isArray(sessionId) ? sessionId[0] : sessionId;
    const body = req.method === "POST" ? await readBody(req) : undefined;

    let entry = sid ? sessions.get(sid) : undefined;
    if (entry) entry.lastSeen = Date.now();
    if (!entry) {
      if (sessions.size >= MAX_HTTP_SESSIONS) {
        res.writeHead(503, { "Content-Type": "text/plain" }).end("Too many open MCP sessions; close one (DELETE) or wait for idle ones to expire.");
        return;
      }
      const transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: () => randomUUID(),
        onsessioninitialized: (id: string) => {
          sessions.set(id, entry!);
        },
      });
      transport.onclose = () => {
        if (transport.sessionId) sessions.delete(transport.sessionId);
      };
      const server = buildServer(bridge, runtime);
      await server.connect(transport);
      entry = { server, transport, lastSeen: Date.now() };
    }
    await entry.transport.handleRequest(req, res, body);
  });

  httpServer.listen(cfg.httpPort, "127.0.0.1", () => {
    log(`streamable-HTTP transport ready on http://127.0.0.1:${cfg.httpPort}/mcp`);
  });
}

async function main(): Promise<void> {
  const cfg = loadConfig();
  const bridge = new BridgeClient(cfg.bridgeUrl, cfg.token, cfg.timeoutMs);

  // Best-effort connectivity hint (does not block startup; the mod may launch later).
  bridge
    .info()
    .then((info) => log("connected to bridge:", JSON.stringify(info)))
    .catch((err) => log("bridge not reachable yet:", (err as Error).message));

  let runtime: AgentRuntime | undefined;
  if (cfg.agent) {
    const dbPath = cfg.dataDir ? defaultDbPath({ MCPFABRIC_DATA_DIR: cfg.dataDir }) : defaultDbPath();
    try {
      runtime = new AgentRuntime(bridge, { dbPath, ...(cfg.world ? { worldOverride: cfg.world } : {}) });
      log(`agent runtime ready (memory: ${dbPath}, opened on first use)`);
    } catch (err) {
      // e.g. a Node build without node:sqlite: keep serving the plain bridge tools.
      log("agent runtime disabled:", (err as Error).message);
    }
  }

  if (cfg.transport === "http") {
    await runHttp(bridge, runtime, cfg);
  } else {
    await runStdio(bridge, runtime);
  }
}

main().catch((err) => {
  log("fatal:", err);
  process.exit(1);
});
