# Agent runtime

The MCP server now includes an agent runtime on top of the bridge tools. The bridge tools are the
agent's "body": move, mine, look. The runtime adds the state an autonomous Minecraft agent needs to
work across many sessions:

- **long-term memory**: notes, places, storage contents, events and skills, with full-text search;
- **a persistent goal tree**;
- **a chunk-level world map** with exploration by frontier;
- **a crafting planner** over the game's own recipes, vanilla and modded;
- **background jobs** that run multi-step actions without the model driving every tick.

All of it is stored per world in one SQLite file (`~/.mcpfabric/agent.db`). The file is created the
first time an agent tool is called, not when the server starts. A new conversation can pick up
where the last one stopped. Call `agent_brief` first.

## Where things live

```
Claude / any MCP client ── reasoning, planning, deciding what to do next
        │ MCP
mcp-server (Node)
  ├─ tools.ts            bridge tools (thin forwarders)          "body" primitives
  └─ agent/              agent runtime                            "memory + cerebellum"
       memory.ts         memories + FTS5 search + ledger
       goals.ts          goal tree
       worldmap.ts       chunk map, frontier, ASCII map
       observe.ts        scan → map + places (incremental "index update")
       planner.ts        recipe graph → craft/smelt steps
       jobs.ts, skills.ts  travel / explore / collect / craft jobs
        │ HTTP /rpc
mod (Fabric / NeoForge) ── perception, actions, tick-level executors (A* walking, mining)
```

The runtime lives in TypeScript and not in the mod on purpose. Every Java feature has to compile
for 14 Minecraft versions × 2 loaders, while the runtime is version-independent and can be unit
tested without a game. The mod only gained what needs game access:

| RPC | Side | What it returns |
|---|---|---|
| `session.info` | client / server | a stable world id: `sp:<save folder>`, `mp:<server address>`, `server:<level name>` |
| `perception.scan` | client | per-chunk surface height, biome and ore/log counts; points of interest (containers, workstations, beds, portals, spawners); positions of requested blocks |
| `perception.blocks` | client | block ids at positions, optionally with the best hotbar tool and whether it can harvest the block |
| `perception.entities` | client | nearby dropped items, hostile/passive mobs and players, nearest first |
| `container.open/state/click/transfer/close` | client | open a block's menu, read it, click or shift-click slots, move items in bulk |
| `recipes.query` | client | recipes producing given items: type, result, one list of accepted ids per slot, `known` (unlocked) |
| `craft.place` | client | place a recipe into the open crafting grid through the recipe book (needs `enablePlayerControl`; capability group `crafting`) |
| `interact.stopBreaking` | client | stop an unfinished survival mining action |

Perception reads the client's own copy of the world. It therefore works on vanilla multiplayer
servers, unlike the server-only `world.*` tools.

## Memory: the codebase-index approach, applied to a world

The design borrows from the `codebase-index` workflow:

| codebase-index | agent memory |
|---|---|
| hybrid search, ranked, compact numbered output | FTS5/BM25 plus proximity, recency and importance, one compact line per memory with an `#id` to cite |
| `index update` after files change | `observe`: rescans loaded chunks, adds new places, marks vanished ones `gone` |
| evidence verification by content hash (`valid/changed/deleted`) | `memory_verify`: each place stores the block id it saw (a fingerprint) and is re-checked against the live world |
| session dedup: `(already sent)` | `recall` does not resend entries this MCP session already received (`fresh:true` repeats them) |

Kinds: `note`, `place`, `container` (last seen contents), `event` (every finished job is logged,
so earlier failures inform later plans), `skill` (a procedure that worked).

## Jobs

`travel_to`, `explore`, `collect_blocks` and `craft_item` start background jobs and return at once.
You can also pass `waitSeconds` to block for a while. Follow a job with `job_status`, which
long-polls, and stop it with `job_cancel`. Only one job runs at a time, because there is one body.
A job belongs to the MCP session that started it: other sessions cannot see or cancel it, and
closing the session (or its idle timeout in HTTP mode) cancels it. Any job stops after 31 minutes.

Every job checks health before each step. It stops with a clear reason when health drops to the
limit (6 by default), so the model can decide whether to retreat, eat or fight.

Jobs also have a **self-defence reflex**, the kind of "mode" Mindcraft runs underneath the language
model. While walking or mining, a job hits any hostile mob within melee range at the attack-cooldown
rate. The exception is creepers, since hitting one does not stop its fuse. Without the reflex, mobs
keep pushing the bot back until navigation gives up.

- **travel_to**: walks in segments of about 40 blocks, since the A* walker has a node budget. It
  fans out to the sides when the straight line is blocked and uses the mapped surface height of
  the chunks along the way.
- **explore**: moves to the best frontier chunk, meaning an unexplored chunk next to an explored
  one. It keeps its previous direction and avoids water, remembers unreachable frontiers, and
  reports new chunks, places and ores.
- **collect_blocks**: finds the nearest *exposed* blocks, picks the best hotbar tool, and refuses
  blocks that no tool it has can harvest. It walks within reach, mines and confirms the block broke.
  It then walks onto the dropped item where it actually landed, found through the entity list,
  because the pickup range is barely more than a block. At the end it sweeps up any drops that
  bounced away.
- **craft_item**: plans with unlocked recipes only, crafts the intermediates (planks → sticks →
  tool) through the recipe book, and uses the 2×2 grid or a crafting table. It walks to a table
  nearby, or places one from the inventory if there is none.

## Known limits

- The walker cannot swim, climb ladders, pillar up or dig tunnels. `collect_blocks` skips buried
  blocks, and travel fails with a clear message when no walkable route exists.
- On 1.21.2+ **multiplayer**, the client only sees the recipes it has unlocked. The rest cannot be
  planned or placed. In singleplayer, the integrated server's full recipe list is used for planning
  (`known: false` marks what the recipe book cannot place yet).
- Smelting and smithing steps are planned but not executed automatically.
- Drops are counted from the inventory difference. Loot tables live on the server, so the block →
  item mapping is not known in advance.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `MCPFABRIC_AGENT` | on | `0` / `false` / `off` serves only the bridge tools |
| `MCPFABRIC_DATA_DIR` | `~/.mcpfabric` | where `agent.db` lives |
| `MCPFABRIC_WORLD` | from the game | force a world id (e.g. to share memory between two saves) |

It requires Node.js ≥ 22.16 for `node:sqlite` with FTS5. From Node 24.15 on there is no
experimental warning; on earlier versions the runtime suppresses that one warning.

## Suggested loop for the model

1. `agent_brief`: where am I, what was I doing.
2. `goal_list`, or `goal_add` with a checkable `doneWhen` and concrete subgoals.
3. `recall` before searching the world. `plan_craft` before gathering.
4. Start a job (`collect_blocks`, `craft_item`, `travel_to`, `explore`) and follow it with
   `job_status`.
5. `observe` after arriving somewhere new. `remember` anything that matters: bases, dangers,
   player requests, procedures that worked.
6. `goal_update` with an outcome, then continue with the next goal.
