# Telemetry

MCP Fabric currently sends **no usage analytics or gameplay data** to the maintainers or to a
third-party metrics service.

## Why is bStats not bundled?

As of 2026-07-29, the official bStats integration wizard does not provide a Fabric platform or a
Fabric Metrics class. Its supported choices are Bukkit/Paper, BungeeCord, Sponge, Velocity,
PocketMine-MP, Hytale, and server implementations.

Using a Bukkit or Velocity Metrics class inside a Fabric mod would be incompatible. Writing a
custom sender and presenting it as bStats would also bypass the official Metrics class and opt-out
contract. MCP Fabric therefore fails closed and does not ship an unofficial bStats integration.

Primary references:

- [bStats integration wizard](https://bstats.org/add-plugin)
- [bStats Metrics classes](https://github.com/Bastian/bStats-Metrics)
- [bStats terms of use](https://bstats.org/terms-of-use)
- [bStats privacy policy and opt-out](https://bstats.org/privacy-policy)

## What would be acceptable later?

A future telemetry change must meet all of these requirements:

1. the service officially supports Fabric and the project's supported Java/Minecraft versions;
2. collection is documented before release and excludes tokens, IPs, player data, chat, commands,
   world data, screenshots, file paths, and MCP prompts;
3. users receive a clear, persistent opt-out that fails closed;
4. network work is asynchronous and cannot delay Minecraft startup or ticks;
5. the implementation and dependency are open to security and privacy review.

Modrinth downloads, followers, and GitHub stars already provide public adoption signals without
adding runtime telemetry.
