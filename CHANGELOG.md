# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
