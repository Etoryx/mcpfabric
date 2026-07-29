<div align="center">
  <img src="docs/assets/mcpfabric-icon-512.png" alt="MCP Fabric grass block, AI network, and bridge emblem" width="180" height="180">

# MCP Fabric — AI control for Minecraft

**Let Claude and other MCP clients see, understand, and play Minecraft through 50+ tools.**

[![Build](https://github.com/Etoryx/mcpfabric/actions/workflows/build.yml/badge.svg)](https://github.com/Etoryx/mcpfabric/actions/workflows/build.yml)
[![Modrinth downloads](https://img.shields.io/modrinth/dt/eA63YgUh?logo=modrinth&label=Modrinth)](https://modrinth.com/mod/mcpfabric)
[![GitHub stars](https://img.shields.io/github/stars/Etoryx/mcpfabric?logo=github&style=flat)](https://github.com/Etoryx/mcpfabric/stargazers)
[![License: MIT](https://img.shields.io/github/license/Etoryx/mcpfabric)](LICENSE)

[Download on Modrinth](https://modrinth.com/mod/mcpfabric) ·
[Installation](#quick-start) ·
[Tools](#tools-50) ·
[Security](SECURITY.md) ·
[Telemetry](docs/TELEMETRY.md) ·
[Contributing](CONTRIBUTING.md)
</div>

MCP Fabric is a local-first Fabric mod and Model Context Protocol server that gives AI agents
structured observation and controlled access to Minecraft. It works on both the client and
dedicated servers across Minecraft 1.21.1–1.21.11 and 26.1–26.2.

- **Play through natural language:** move, look, navigate, mine, build, fight, and use inventory.
- **See the game:** inspect blocks, entities, players, status, chat, events, and screenshots.
- **Operate servers:** run commands, edit worlds, manage entities, and administer players.
- **Bring your own AI:** works with Claude Desktop, Claude Code, and other MCP-compatible hosts.
- **Stay local by default:** loopback-only HTTP bridge, bearer authentication, and capability gates.
- **No hidden telemetry:** the current Fabric release sends no usage analytics.

## Quick start

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) and
   [Fabric API](https://modrinth.com/mod/fabric-api).
2. Download the jar matching your Minecraft version from
   [Modrinth](https://modrinth.com/mod/mcpfabric/versions) and place it in `mods/`.
3. Launch Minecraft once, then copy `token` from `config/mcpfabric.config.json`.
4. Build the MCP server with `cd mcp-server && npm ci && npm run build`.
5. Add it to your MCP client using the [ready-to-copy examples](#3-connect-to-claude).

> [!CAUTION]
> MCP Fabric can grant an AI operator-level control. Keep the bridge on `127.0.0.1`, keep
> authentication enabled, and disable capability groups you do not need.

## How it works

`mcpfabric` has two parts: a Fabric mod that embeds a local HTTP bridge in Minecraft, and a small
TypeScript MCP server that exposes the bridge as discoverable tools.

```
Claude / any MCP client
        │  MCP (stdio or streamable HTTP)
   mcp-server  (Node / TypeScript)
        │  HTTP  POST /rpc (JSON-RPC) + GET /events (SSE),  bearer token, 127.0.0.1 only
   Fabric mod "mcpfabric"  (HTTP server embedded in Minecraft)
        │  all game access goes through the main-thread executor (server.execute / Minecraft.execute)
   ┌── common (env *) ─────────────┐   ┌── client (env client) ─────────────────┐
   │ info  world  entities         │   │ player  control  interact               │
   │ players  command  chat events │   │ inventory  vision  navigation  chat     │
   └───────────────────────────────┘   └─────────────────────────────────────────┘
```

Reads use Minecraft's native API (structured data); writes (`setblock` / `summon` / `give` / `tp` /
effects / weather / time) go through the command dispatcher with output capture. Player control uses
`KeyMapping` (integrating with the vanilla input pipeline), screenshots use the vanilla
`Screenshot` / `NativeImage`, and navigation is a custom A\*.

---

## Supported Minecraft versions

A single source tree targets many Minecraft versions using
[Stonecutter](https://stonecutter.kikugie.dev/). Each version below ships its own jar:

| Line     | Versions (one jar each)                                          | Java |
|----------|-----------------------------------------------------------------|------|
| 1.21.x   | 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11 | 21 |
| 26.x     | 26.1.2 (installs on 26.1–26.1.2), 26.2                           | 25   |

13 jars are produced, each named `mcpfabric-<modVersion>+<mcVersion>.jar` (e.g.
`mcpfabric-0.2.0+1.21.8.jar`). The 26.1.2 jar declares compatibility with the whole 26.1 line.
Requires **Fabric Loader ≥ 0.19.3** and the matching **Fabric API** build, plus **Node.js ≥ 20**
for the MCP server.

---

## 1. Build the mod

```bash
# Build a single version (the one currently active in stonecutter.gradle)
./gradlew build           # Windows: gradlew.bat build

# Build a specific version
./gradlew ":1.21.8:build"

# Build every supported version at once
./gradlew chiseledBuild
```

Per-version jars land in `versions/<mcVersion>/build/libs/`.

> **JDK note.** 1.21.x builds need **JDK 21**; the 26.x line needs **JDK 25**. Loom requires the
> Gradle daemon to run on a JDK at least as new as the Minecraft version, so to build 26.x (or
> `chiseledBuild`) run Gradle on JDK 25 with JDK 21 also installed. See
> [CONTRIBUTING.md](CONTRIBUTING.md#jdk-requirements).

> **Network note.** If the first build fails with `Remote host terminated the handshake` while
> downloading dependencies from `maven.fabricmc.net` (this happens behind TLS-inspecting
> antivirus/firewalls), `gradle.properties` already pins TLS 1.2 and sequential downloads. Just
> re-run the build — the download cache persists.

### Install

Drop the jar for your Minecraft version, together with **Fabric API**, into your `mods/` folder:

- **Client** (AI plays as you): the `mods/` folder of your Fabric instance.
- **Server** (AI as admin): the `mods/` folder of your dedicated Fabric server.
- Both sides at once is fine.

On first launch the mod creates `config/mcpfabric.config.json` and logs where to find the token:

```
[mcpfabric] ready — bridge http://127.0.0.1:25599 (token: .../config/mcpfabric.config.json)
```

Copy the `token` value from that file — the MCP server needs it. The token is intentionally not
printed to logs.

### Mod config (`config/mcpfabric.config.json`)

```json
{
  "host": "127.0.0.1",
  "port": 25599,
  "token": "generated automatically",
  "requireAuth": true,
  "callTimeoutMs": 8000,
  "enableWorldWrite": true,
  "enableCommands": true,
  "enablePlayerControl": true,
  "enableVision": true
}
```

The `enable*` flags let you switch off dangerous capability groups. `requireAuth` (default `true`)
gates every request behind the bearer token — only set it to `false` if you understand that it
removes the sole authentication on an operator-level bridge. Keep `host` on `127.0.0.1` unless you
fully understand the consequences — the bridge grants operator-level power.

---

## 2. MCP server

```bash
cd mcp-server
npm install
npm run build      # compiles to dist/
```

Usually your MCP client launches it for you (see below). Manually:

```bash
MCPFABRIC_URL=http://127.0.0.1:25599 MCPFABRIC_TOKEN=<token> node dist/index.js
```

### Environment variables

| Variable               | Default                  | Description                                       |
|------------------------|--------------------------|---------------------------------------------------|
| `MCPFABRIC_URL`        | `http://127.0.0.1:25599` | Address of the mod's HTTP bridge.                 |
| `MCPFABRIC_TOKEN`      | —                        | Bearer token from the mod config (required by default). |
| `MCPFABRIC_TIMEOUT_MS` | `15000`                  | Per-call timeout to the bridge.                   |
| `MCPFABRIC_TRANSPORT`  | `stdio`                  | `stdio` or `http`.                                |
| `MCPFABRIC_HTTP_PORT`  | `25600`                  | Port for the `http` transport (`/mcp`).           |

---

## 3. Connect to Claude

### Claude Desktop

`claude_desktop_config.json` (see `examples/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "mcpfabric": {
      "command": "node",
      "args": ["/absolute/path/to/mcpfabric/mcp-server/dist/index.js"],
      "env": {
        "MCPFABRIC_URL": "http://127.0.0.1:25599",
        "MCPFABRIC_TOKEN": "paste-the-token-from-the-mod-log"
      }
    }
  }
}
```

### Claude Code

Use the `.mcp.json` in the project root (see `examples/mcp.json`) or:

```bash
claude mcp add mcpfabric -- node /absolute/path/to/mcpfabric/mcp-server/dist/index.js
```

Then set `MCPFABRIC_TOKEN` in the server's environment.

---

## Tools (50+)

**info** — `get_status`, `list_capabilities`
**world (read)** — `get_block`, `get_blocks_region`, `find_blocks`, `get_time_and_weather`, `list_dimensions`, `raycast`
**world (write)** — `set_block`, `fill_blocks`, `set_time`, `set_weather`
**entities** — `query_entities`, `get_entity`, `summon_entity`, `remove_entity`
**players (admin)** — `list_players`, `get_player`, `teleport_player`, `set_gamemode`, `give_item`, `apply_effect`, `message_player`, `kick_player`
**command** — `run_command` (any operator-level command, with output capture)
**chat** — `send_chat`, `get_recent_chat`
**player (local, client)** — `get_self`, `get_inventory`, `get_equipment`, `get_status_effects`
**control (client)** — `set_movement`, `stop_movement`, `look`, `look_at`, `jump`, `start_using_item`, `stop_using_item`
**interact (client)** — `break_block`, `place_block`, `use_item`, `attack_entity`, `use_entity`, `drop_held_item`
**inventory (client)** — `select_hotbar_slot`, `drop_slot`, `swap_slots`
**vision (client)** — `screenshot` (PNG for vision models), `describe_scene`
**navigation (client)** — `navigate_to` (A\*), `navigation_status`, `stop_navigation`
**events** — `poll_events` (recent damage, deaths, chat, spawns, player join/leave)

Server tools require a running server (integrated on the client or dedicated). Client tools
(`control` / `interact` / `vision` / `navigation` / `player` / `inventory`) only work on the client.
Call `get_status` first — it reports which side you are on and which groups are available.

---

## Example prompts

- "Look around and describe what's nearby" → `get_self` + `describe_scene` (+ `screenshot` for a vision model).
- "Walk to these coordinates and mine diamonds" → `find_blocks` → `navigate_to` → `break_block`.
- "What's happening on the server" → `list_players` + `poll_events`.
- "Build a wall" → `fill_blocks` or a series of `set_block` / `run_command`.

---

## Security

- The bridge listens on **`127.0.0.1` only** and requires a **bearer token**.
- Operator-level capabilities (`run_command`, world writes, player control) are **on by default** —
  this gives the AI full control. Turn off groups with the `enable*` flags if you need to.
- Do not expose `host` externally without understanding the risk and putting an authenticated
  reverse proxy in front of it. See [SECURITY.md](SECURITY.md).

---

## Project structure

```
mcpfabric/
├─ settings.gradle, stonecutter.gradle, build.gradle   # Stonecutter multi-version + Fabric Loom
├─ gradle.properties                                   # shared build config
├─ versions/<mc>/gradle.properties                     # per-version Minecraft + Fabric API
├─ src/main/java/dev/mcpfabric/                         # common (env *): bridge + server handlers
│  ├─ McpFabric.java, ServerHolder.java
│  ├─ bridge/      (HttpBridgeServer, RpcRouter, MainThread, SseHub, Json, ...)
│  ├─ config/      (McpConfig)
│  ├─ events/      (EventBus, GameEvent)
│  └─ handlers/    (Info/World/Entity/PlayerAdmin/Command/Chat + support/CommandRunner, Levels)
├─ src/client/java/dev/mcpfabric/client/                # client (env client): bot + client handlers
│  ├─ McpFabricClient.java, ClientMc.java, BotController.java, ClientEvents.java
│  ├─ nav/AStarPathfinder.java
│  └─ handlers/    (LocalPlayer/Control/Interact/Inventory/Vision/Nav/ClientChat)
└─ mcp-server/                                          # MCP server (TypeScript)
   ├─ src/index.ts, bridge.ts, tools.ts, config.ts
   └─ package.json, tsconfig.json
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the multi-version workflow and how to add a Minecraft
version.

## License

[MIT](LICENSE).
