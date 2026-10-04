# Contributing to mcpfabric

Thanks for your interest in improving mcpfabric! This document explains the project layout,
the multi-version build, and how to get a change merged.

## Project layout

```
mcpfabric/
├─ build.gradle              # Fabric build (Loom), applied to every Fabric node
├─ build.neoforge.gradle     # NeoForge build (ModDevGradle), applied to every NeoForge node
├─ gradle/common.gradle      # java/jar/publishing + loader source filtering, shared by both
├─ gradle/modrinth.gradle    # Modrinth publishing, shared by both
├─ stonecutter.gradle        # Stonecutter controller (active version, chiseled tasks)
├─ settings.gradle           # declares the nodes (Minecraft version × loader)
├─ gradle.properties         # shared build config (loom/loader/mod version)
├─ versions/<node>/gradle.properties # per-node Minecraft + loader versions
├─ src/main/java/…           # common code (runs on client and dedicated server)
├─ src/client/java/…         # client-only code (bot control, vision, navigation)
└─ mcp-server/               # TypeScript MCP server that talks to the in-game bridge
```

## Multi-version with Stonecutter

The mod targets many Minecraft versions from a single source tree using
[Stonecutter](https://stonecutter.kikugie.dev/). Each supported version is a *node* declared in
`settings.gradle`; all nodes share `src/`, and per-version API differences are expressed with
Stonecutter comments.

### Building

```bash
# Build the currently active version (see stonecutter.gradle)
./gradlew build

# Build one specific version
./gradlew ":1.21.5:build"

# Build every supported version at once (what CI runs)
./gradlew chiseledBuild
```

Per-version jars land in `versions/<node>/build/libs/`.

#### JDK requirements

Minecraft's required Java version differs across the range: **1.21.x needs Java 21**, the **26.x line
needs Java 25**. Loom requires the *Gradle daemon itself* to run on a JDK at least as new as the
Minecraft version being built. So:

- Building only 1.21.x: run Gradle on **JDK 21** (or newer).
- Building 26.x, or `chiseledBuild` (all versions): run Gradle on **JDK 25**, and keep a JDK 21
  installed so Gradle can target 1.21.x via toolchains. Point Gradle at JDK 25 with `JAVA_HOME` or
  `org.gradle.java.home` — you must install that JDK yourself, as the resolver below cannot
  provision the JDK the Gradle daemon runs on. The
  [foojay toolchain resolver](https://github.com/gradle/foojay-toolchains) (configured in
  `settings.gradle`) only auto-provisions the per-version *compile* toolchains.

### Version-conditional code

Use Stonecutter comments to fork code by Minecraft version. The condition is a semver range
compared against the node being built:

```java
//? if >=1.21.2 {
p.getInventory().setSelectedSlot(slot);
//?} else
/*p.getInventory().selected = slot;*/
```

Keep the *active* (uncommented) branch targeting the newest supported version, and put older-API
fallbacks in the commented branch. Prefer narrow conditions tied to the exact version where an API
changed. When you bump or add a version, run `./gradlew chiseledBuild` and fix any node that fails
to compile.

### Mod loaders (Fabric and NeoForge)

Fabric nodes are named after the Minecraft version (`1.21.8`); NeoForge nodes are named
`<mc>-neoforge` (`1.21.1-neoforge`) and use `build.neoforge.gradle`. Nearly all code is shared and
talks to vanilla Minecraft only. Loader APIs are confined to:

- `dev.mcpfabric.platform.Platform` — the few loader services the shared code needs (config dir,
  side, versions), implemented per loader;
- packages named after a loader — `dev.mcpfabric.fabric`, `dev.mcpfabric.neoforge`,
  `dev.mcpfabric.client.fabric`, `dev.mcpfabric.client.neoforge` — holding the entrypoints, which
  subscribe to that loader's events and forward them to `McpFabric`, `GameEvents`,
  `McpFabricClient` and `ClientEvents`.

Each node compiles only its own loader's packages (see `gradle/common.gradle`), so a new game event
means one hook in the shared class plus one subscription per loader. For the rare in-file difference,
the Stonecutter constants `fabric` / `neoforge` are available (`//? if neoforge {`).

### Adding a new Minecraft version

1. Add the version to the `versions(...)` list in `settings.gradle`.
2. Create `versions/<mc>/gradle.properties` with `minecraft_version`, `fabric_api_version`,
   `minecraft_dep`, and `java_version` (look up the Fabric API build on Modrinth and the required
   Java version in the Mojang version manifest).
3. Run `./gradlew :<mc>:build` and add Stonecutter conditionals for any compile error.

For NeoForge, add `version('<mc>-neoforge', '<mc>').buildscript('build.neoforge.gradle')` to
`settings.gradle` and create `versions/<mc>-neoforge/gradle.properties` with `minecraft_version`,
`neoforge_version`, `minecraft_dep` (a Maven range, e.g. `[1.21.1]`) and `java_version`. Build with
`./gradlew :<mc>-neoforge:build`; `:<mc>-neoforge:runServer` / `runClient` start a dev instance.

## MCP server

```bash
cd mcp-server
npm install
npm run typecheck
npm run build
```

`src/tools.ts` is the single source of truth for the tool catalogue. When you add or rename a Java
RPC handler, keep the corresponding tool entry in sync.

## Pull requests

- Keep changes focused; one logical change per PR.
- Match the surrounding code style (tabs in Java, the existing formatting in TypeScript).
- Make sure `./gradlew chiseledBuild` and `npm run typecheck` pass.
- Describe what you changed and which Minecraft versions you tested against.

## License

By contributing you agree that your contributions are licensed under the project's
[MIT License](LICENSE).
