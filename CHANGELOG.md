# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
