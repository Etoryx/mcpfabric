# Modrinth listing for MCP Fabric

This file is the canonical copy and settings checklist for
[MCP Fabric on Modrinth](https://modrinth.com/mod/mcpfabric).

## Project settings

| Field | Value |
| --- | --- |
| Project ID | `eA63YgUh` |
| Slug | `mcpfabric` |
| Project type | Mod |
| License | MIT |
| Categories | Game Mechanics, Management, Utility |
| Client side | Optional |
| Server side | Optional |
| Source | `https://github.com/Etoryx/mcpfabric` |
| Issues | `https://github.com/Etoryx/mcpfabric/issues` |
| Icon | `docs/assets/mcpfabric-icon-512.png` |

Client and server are both **optional**, not required: install on the client when the AI should play
as the local player, on a dedicated server when it should act as an operator, or on both.

## Summary

Give AI agents full control of Minecraft through MCP: 50+ tools for observation, movement, building,
vision, and server administration on Fabric.

## Description

**MCP Fabric is a local-first Fabric mod and Model Context Protocol server that lets AI agents
observe, understand, and control Minecraft through 50+ structured tools.** Connect Claude or another
MCP client to play as your character, inspect the world, or operate a server.

### What can an AI do with MCP Fabric?

An AI can move, look, navigate, mine, build, fight, manage inventory, inspect blocks and entities,
capture screenshots, read live events, edit worlds, run commands, and administer players.

- **Natural-language gameplay:** movement, interaction, A* navigation, combat, and inventory.
- **Structured observation:** blocks, entities, players, chat, events, status, and screenshots.
- **Server operations:** world edits, commands, time/weather, entities, and player administration.
- **Any MCP host:** designed for Claude Desktop, Claude Code, and other compatible clients.
- **Local-first security:** loopback binding, bearer authentication, and capability gates.

### Where does it run?

| Install location | What the AI can do |
| --- | --- |
| Client | Play as the local player and use vision, control, interaction, inventory, and navigation |
| Dedicated server | Operate the world, entities, commands, events, and players |
| Both | Use all capabilities appropriate to the active side |

Each supported Minecraft version has its own jar. MCP Fabric supports Minecraft 1.21.1–1.21.11 and
26.1–26.2, Fabric Loader 0.19.3 or newer, and the matching Fabric API.

### How do I install MCP Fabric?

1. Install Fabric Loader and Fabric API.
2. Download the MCP Fabric jar matching your exact Minecraft version and place it in `mods/`.
3. Launch once and copy `token` from `config/mcpfabric.config.json`.
4. Build the small Node.js MCP server and add it to your MCP host.

Follow the [five-step quick start](https://github.com/Etoryx/mcpfabric#quick-start) for copy-ready
commands and client configuration.

> MCP Fabric can grant operator-level control. Keep the bridge on `127.0.0.1`, keep authentication
> enabled, and disable capability groups you do not need. Read the
> [security policy](https://github.com/Etoryx/mcpfabric/blob/main/SECURITY.md) before exposing it
> beyond your own machine.

### Example prompts

- “Look around and describe what is nearby.”
- “Walk to these coordinates and mine the nearest diamonds.”
- “Tell me what is happening on the server.”
- “Build a stone wall between these two points.”

Source code, documentation, releases, and contribution guidelines are available on
[GitHub](https://github.com/Etoryx/mcpfabric).

## Gallery plan

Upload at least three original screenshots with descriptive captions:

1. **AI scene understanding** — Minecraft view beside the structured `describe_scene` result.
2. **Natural-language building** — prompt, in-progress structure, and finished result.
3. **50+ MCP tools** — MCP Inspector or client tool list with Minecraft visible in the background.

Do not include bridge tokens, private paths, player chat, or server addresses in screenshots.
