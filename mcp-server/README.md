# mcpfabric MCP server

Bridges an MCP client (Claude Desktop / Claude Code / any MCP host) to the in-game HTTP bridge
exposed by the **mcpfabric** mod (Fabric / NeoForge), publishing ~50 tools to observe and control
Minecraft, plus an agent runtime with persistent memory, goals, a world map and background jobs
(see [../docs/AGENT.md](../docs/AGENT.md)). Requires Node.js ≥ 22.16.

## Install & build

```bash
npm install
npm run build       # -> dist/index.js
npm run typecheck   # tsc --noEmit (sources and tests)
npm test            # unit tests + agent jobs against a simulated world
```

## Run

The MCP client normally launches this process. Manually:

```bash
MCPFABRIC_URL=http://127.0.0.1:25599 MCPFABRIC_TOKEN=<token> node dist/index.js
```

## Environment

| Variable               | Default                  | Meaning                                        |
|------------------------|--------------------------|------------------------------------------------|
| `MCPFABRIC_URL`        | `http://127.0.0.1:25599` | In-game bridge base URL.                        |
| `MCPFABRIC_TOKEN`      | —                        | Bearer token from `config/mcpfabric.config.json`. |
| `MCPFABRIC_TIMEOUT_MS` | `15000`                  | Per-call timeout.                               |
| `MCPFABRIC_TRANSPORT`  | `stdio`                  | `stdio` (default) or `http`.                    |
| `MCPFABRIC_HTTP_PORT`  | `25600`                  | Port for the streamable-HTTP transport (`/mcp`).|
| `MCPFABRIC_AGENT`      | on                       | `0`/`false`/`off` disables the agent runtime tools. |
| `MCPFABRIC_DATA_DIR`   | `~/.mcpfabric`           | Directory of the agent database (`agent.db`).   |
| `MCPFABRIC_WORLD`      | reported by the game     | Force the world id that keys agent memory.      |

## Architecture

`src/tools.ts` is the single source of truth for the tool catalogue: each entry maps an MCP tool
to a bridge RPC `method` plus a zod input schema. `src/index.ts` registers every entry generically
(forwarding args to `POST /rpc`) and renders results as JSON, except `screenshot` which returns an
image content block. `src/bridge.ts` is the HTTP client; `src/config.ts` reads env config.

`src/agent/` is the agent runtime: its tools (`agent/tools.ts`) run in this process, store state in
SQLite (`node:sqlite`) and drive the game through the same bridge. `test/fake-world.ts` simulates
the bridge contract so jobs are tested without a game.

Keep tool names/methods in sync with the Java handler registry in the mod
(`dev.mcpfabric.handlers.*` and `dev.mcpfabric.client.handlers.*`).
