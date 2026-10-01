# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.5.0] - 2026-10-01

Fabric and NeoForge. Thanks to @heide-oficial for the fixes and hardening in #29, #30 and #31.

### Added
- **Agent runtime** in the MCP server ([docs/AGENT.md](docs/AGENT.md)). Everything is stored per
  world in SQLite and survives restarts:
  - long-term memory with full-text search, ranked by proximity and recency, plus re-verification of
    remembered blocks against the live world;
  - a persistent goal tree;
  - a chunk map with an ASCII view and frontier exploration;
  - a crafting planner over the game's own (including modded) recipes;
  - background jobs `travel_to`, `explore`, `collect_blocks` and `craft_item`, which stop when
    health gets low and fight back against hostile mobs in melee range.
- New client RPCs for the runtime, working on any server: `session.info`, `perception.scan`,
  `perception.blocks`, `perception.entities`, `container.open/state/click/transfer/close`, `recipes.query`, `craft.place`
  and `interact.stopBreaking`. `session.info` also works on dedicated servers.
- `info.capabilities` reports the new `perception`, `containers` and `recipes` groups.
- `describe_scene` lists the entities in view. `navigation_status` reports `stuck` and
  `ticksWithoutProgress` (#31).
- `query_entities` returns equipment and key attributes of living entities, plus the `center` it
  searched around and `centerSource`. `list_dimensions` says which dimension each player is in, and
  `raycast` reports the face it hit (#31).
- Survival mining gives up by itself when the block cannot break (out of reach, unbreakable, no
  progress) and reports a `mining_finished` event (#30).
- Unit tests: JUnit tests for the bridge run in every node's build, and a contract test keeps the
  MCP tools and the mod's bridge methods in sync (#29, #31).

### Changed
- The MCP server now requires Node.js ≥ 22.16 (built-in `node:sqlite` with FTS5). CI runs it on
  Node 24 along with the new unit tests.
- `break_block`, `place_block` and `attack_entity` report what the integrated server actually did
  (`broke`, `placed`, `damaged`, `serverHealth`). Out-of-reach actions are not sent at all and return
  `OUT_OF_REACH` (#30).
- `break_block` in `instant` mode in survival now needs an integrated server with world writes
  enabled, where the server breaks the block. Before, it only predicted the break on the client (#30).
- `use_item` behaves like the use key: it acts on the entity or block under the crosshair first,
  then uses the item on its own (#30).
- Every tool respects its capability lock. Movement, looking, inventory changes, vision, `/` commands
  in chat and the player admin tools are refused when their lock is off. `get_status` lists
  `inventory` under `enablePlayerControl` and `players_admin` under `enableCommands`. Block and
  entity data (`{...}`) in `set_block`, `fill_blocks` and `summon_entity` needs `enableCommands` (#31).
- `find_blocks` searches nearest-first and reports `searchedRadius`. `get_blocks_region` refuses
  regions larger than 16,777,216 positions. `raycast` uses vanilla outline shapes and stops at
  unloaded chunks (#31).
- The MCP server reports its real package version, now kept in step with the mod (#29).

### Fixed
- A param of the wrong type is reported as `bad_request` naming the param, not as an internal
  error (#31).
- A game-thread task that times out before it starts is cancelled instead of running later (#31).
- Coordinates in generated commands are no longer written in scientific notation (`1.0E-4`), which
  commands reject (#31).

### Security
- The HTTP bridge refuses requests from web pages (any `Origin` header), requires
  `Content-Type: application/json` for RPC calls, caps request bodies at 1 MiB and caps open event
  streams at 16 (#29).
- The MCP server's HTTP mode refuses non-local `Host` and `Origin` headers (DNS rebinding),
  closes sessions idle for 30 minutes and caps them at 32 (#29).

## [0.4.1] - 2026-09-25

This is the first Fabric release since 0.3.0, so it also includes the 0.4.0 changes (see the 0.4.0
section of CHANGELOG.md): the Platform layer and `"loader"` in `info.status`.

### Fixed
- Closing the game no longer crashes with `Watchdog (Client shutdown from post-main)`. The client
  now stops the HTTP bridge when it shuts down. The bridge's non-daemon `HTTP-Dispatcher` thread
  had been keeping the JVM alive until the shutdown watchdog fired (#24). Fabric and NeoForge.

## [0.4.0] - 2026-09-23

### Added
- **NeoForge support.** 13 NeoForge jars (`mcpfabric-neoforge-<version>+<mc>.jar`) for Minecraft
  1.21.1, 1.21.3–1.21.11, 26.1.2, 26.2 and 26.3, with the same tools, bridge and config as the
  Fabric build, published to the same Modrinth project as their own releases. 1.21.2 is not
  covered (NeoForge only shipped two abandoned betas for it).
- `info.status` now reports the mod loader (`"loader": "fabric"` / `"neoforge"`).

### Changed
- Loader-specific code is isolated behind a small `Platform` layer and per-loader entrypoints
  (`dev.mcpfabric.fabric`, `dev.mcpfabric.neoforge`); everything else is shared. The Fabric
  entrypoints moved to `dev.mcpfabric.fabric.FabricEntrypoint` and
  `dev.mcpfabric.client.fabric.FabricClientEntrypoint`.
- Fabric and NeoForge are released independently: a `vX.Y.Z` tag publishes only Fabric, a
  `neoforge-vX.Y.Z` tag only NeoForge (`publishFabric` / `publishNeoForge`, `buildFabric` /
  `buildNeoForge`). `publishMods` is gone.
- NeoForge nodes build against binary-patched Minecraft (ModDevGradle `disableRecompilation`), which
  keeps the 13 extra nodes fast and light on memory in CI.

### Fixed
- Client RPCs sent while the game is still starting return `no_client_player` instead of an
  internal `NullPointerException` (on NeoForge the bridge starts before Minecraft exists).

## [0.3.0] - 2026-09-23

### Added
- **Minecraft 26.3 support.** A new 26.3 jar is built and published alongside the existing ones
  (14 Minecraft versions in total).
- The 26.2 jar is now published on Modrinth (the code has supported 26.2 since 0.2.0, but the
  0.2.1 release never reached Modrinth).
- The 26.1.2 jar is also tagged for 26.1 and 26.1.1 on Modrinth, matching its declared
  compatibility.

### Changed
- Fabric Loader requirement raised to 0.19.5; Fabric API updated to the latest builds for 1.21.1,
  1.21.11, 26.1.2 and 26.2.
- Build tooling: Stonecutter 0.9.8, Gradle 9.6.1.
- MCP server: zod 4, TypeScript 7 and `@types/node` 26.
- CI and release workflows use the latest `setup-java`, `setup-node`, `setup-gradle` and
  `action-gh-release`.

### Fixed
- `interact.dropItem` now respects `enablePlayerControl`, like every other player-control action.

## [0.2.1] - 2026-07-30

### Fixed
- The bot's per-tick input driver forced movement key state every client tick, even when idle,
  permanently overriding the player's own WASD/jump/sneak/sprint input once the mod started
  ticking. Keys are now only forced while a movement command or navigation is active, and
  released back to the keyboard once it stops.

### Added
- A production-ready project icon for Fabric metadata, Modrinth, and GitHub presentation.
- Repository community files and a canonical Modrinth listing guide.

### Changed
- Project links now point to the canonical `Etoryx/mcpfabric` repository.
- GitHub Actions are pinned to immutable commit SHAs and release publishing fails closed when the
  Modrinth token is unavailable.
- The MCP server lockfile version now matches the package version.
- The MCP SDK and vulnerable transitive packages were updated; `npm audit` reports zero known
  vulnerabilities.

### Security
- The bridge bearer token is no longer printed to logs; read it from
  `config/mcpfabric.config.json`.

## [0.2.0] - 2026-06-19

### Added
- **Multi-version support** via [Stonecutter](https://stonecutter.kikugie.dev/): the mod now builds
  for Minecraft 1.21.1–1.21.11 and the 26.x line (26.1.x, 26.2) from a single source tree.
  Per-version jars are produced as `mcpfabric-<modVersion>+<mcVersion>.jar`.
- `./gradlew chiseledBuild` to build every supported version; per-version configuration lives in
  `versions/<mcVersion>/gradle.properties`.
- GitHub Actions CI building all versions and type-checking the MCP server.
- Automated releases: pushing a `v*` tag builds every supported version, publishes them to Modrinth
  (one version per Minecraft release), and creates a GitHub Release with all jars attached.
- `CONTRIBUTING.md`, `SECURITY.md`, `docs/RELEASING.md`, issue/PR templates, Dependabot config.

### Changed
- Build upgraded to Fabric Loom 1.17.x and Gradle 9.5.x.
- README is now in English.

## [0.1.0]

- Initial single-version (Minecraft 1.21.8) release: Fabric mod with an embedded HTTP bridge and a
  TypeScript MCP server exposing ~50 tools for full read & control of Minecraft.
