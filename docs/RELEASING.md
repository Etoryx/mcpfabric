# Releasing mcpfabric

This project ships one mod, built for every supported Minecraft version from a single source tree
([Stonecutter](https://stonecutter.kikugie.dev/)). A release builds all of those versions, publishes
each to Modrinth, and creates a GitHub Release with every jar attached — all driven by pushing a
single git tag.

**Fabric and NeoForge are released independently**, into the same Modrinth project: a `vX.Y.Z` tag
releases only Fabric, a `neoforge-vX.Y.Z` tag releases only NeoForge. Each gets its own GitHub
Release with only its own jars.

## How a release flows

```
git tag vX.Y.Z           ──push──▶  .github/workflows/release.yml
git tag neoforge-vX.Y.Z               ├─ verify tag == mod_version
                                      ├─ ./gradlew buildFabric   | buildNeoForge    (that loader's MC versions)
                                      ├─ ./gradlew publishFabric | publishNeoForge  (upload each to Modrinth)
                                      └─ GitHub Release with that loader's jars attached
```

Each Minecraft version becomes its own Modrinth version, targeting exactly the Minecraft version(s)
and loader it was built for: `X.Y.Z+<mcVersion>` for Fabric (for example `0.2.0+1.21.8`) and
`X.Y.Z+<mcVersion>-neoforge` for NeoForge. Users filtering Modrinth by Minecraft version and loader
get the correct jar.

Both loaders share `mod_version` and the CHANGELOG section. Releasing one loader does not require
releasing the other; to ship a loader-only fix, bump `mod_version` and tag just that loader.

## One-time setup

You only do this once, before the first published release.

### 1. Verify the Modrinth project

The project already exists at <https://modrinth.com/mod/mcpfabric> with ID `eA63YgUh`.

Confirm that [`gradle.properties`](../gradle.properties) contains:

```properties
modrinth_id=eA63YgUh
```

Keep the public project metadata aligned with [`MODRINTH.md`](MODRINTH.md), upload
[`mcpfabric-icon-512.png`](assets/mcpfabric-icon-512.png) as the icon, and add Fabric API as a
required dependency.

### 2. Create a Modrinth token

1. Modrinth → **Account settings → PATs** (Personal Access Tokens).
2. Create a token with at least the **Create versions** scope. Copy it once — it is shown only once.

### 3. Add the token as a GitHub secret

Repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Name             | Value                     |
| ---------------- | ------------------------- |
| `MODRINTH_TOKEN` | the PAT created in step 2 |

> The release workflow fails closed when `MODRINTH_TOKEN` or `modrinth_id` is missing. It will not
> create a GitHub-only partial release by accident.

## Cutting a release

1. **Bump the version** in [`gradle.properties`](../gradle.properties):

   ```properties
   mod_version=0.3.0
   ```

   Version scheme: the jar/Modrinth version is `mod_version+minecraft_version` (plus `-neoforge` on
   Modrinth for NeoForge). The git tag carries only the loader prefix and mod version (`v0.3.0`,
   `neoforge-v0.3.0`). For pre-releases use a suffix — `0.3.0-beta.1`, `0.3.0-rc.1` —
   which the workflow flags as a GitHub pre-release automatically.

2. **Update [`CHANGELOG.md`](../CHANGELOG.md)**: move the `[Unreleased]` notes into a new
   `## [0.3.0] - YYYY-MM-DD` section. The Modrinth version notes are pulled from the section whose
   header matches `mod_version`, so this header must exist.

3. **Commit, tag, push:**

   ```bash
   git add gradle.properties CHANGELOG.md
   git commit -m "chore: release v0.3.0"
   git push
   git tag v0.3.0                      # Fabric
   git push origin v0.3.0
   git tag neoforge-v0.3.0             # NeoForge (now, or whenever it is ready)
   git push origin neoforge-v0.3.0
   ```

   The tag must match `mod_version` exactly, or the workflow fails fast with a clear message.

4. Watch the **Release** workflow in the Actions tab. On success you get the Modrinth versions and a
   GitHub Release.

### Manual / re-run

The workflow also has a `workflow_dispatch` trigger (Actions tab → **Release** → **Run workflow**),
which takes the loader and the version (without the leading `v`) and creates the tag if it does not
yet exist.
Useful for re-running a failed publish.

## Build locally without publishing

```bash
./gradlew chiseledBuild        # build every node → versions/<node>/build/libs/*.jar
./gradlew buildFabric          # build every Fabric node (buildNeoForge: every NeoForge node)
./gradlew publishFabric        # debug mode unless MODRINTH_TOKEN is set; uploads nothing locally
./gradlew publishNeoForge      # same, for NeoForge
./gradlew :1.21.8:build        # build a single version
```

## Adding a new Minecraft version

1. Add the version to the `versions(...)` list in [`settings.gradle`](../settings.gradle).
2. Create `versions/<newVersion>/gradle.properties` (copy an existing one; set `minecraft_version`,
   `fabric_api_version`, `minecraft_dep`, `java_version`). NeoForge nodes: see CONTRIBUTING.md.
3. Resolve any source differences with Stonecutter `//?` comments.

It is then built and published automatically by the next release — no workflow changes needed.

## Enabling CurseForge later

CurseForge is intentionally left out for now. To add it:

1. Create the CurseForge project and note its **numeric project ID**.
2. Add the [`net.darkhax.curseforgegradle`](https://github.com/Darkhax-Minecraft/CurseForgeGradle)
   plugin to `stonecutter.gradle` (apply false) and apply it in `build.gradle`, registering a
   `curseforge` task per node that uploads `remapJar` with the node's `minecraft_version`.
3. Make the `publishFabric` / `publishNeoForge` tasks in `stonecutter.gradle` also depend on each
   node's `curseforge` task.
4. Add a `CURSEFORGE_TOKEN` GitHub secret and pass it through `env:` in the publish step of
   `release.yml`.
