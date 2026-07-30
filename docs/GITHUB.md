# GitHub repository settings

The files in this repository cover CI, security scanning, issue forms, pull requests, ownership,
support, conduct, contributing, releases, and security reporting. The remaining presentation and
governance settings live in GitHub and require repository-admin access.

## About section

| Field | Value |
| --- | --- |
| Description | Control Minecraft with AI through MCP — a Fabric mod and 50+ tools for gameplay, vision, automation, and server administration. |
| Website | `https://modrinth.com/mod/mcpfabric` |
| Topics | `minecraft`, `minecraft-mod`, `fabric`, `fabricmc`, `mcp`, `model-context-protocol`, `ai-agent`, `automation`, `java`, `typescript` |
| Social preview | `docs/assets/mcpfabric-icon-512.png` |

Enable **Releases**, **Issues**, and **Discussions**. Use Discussions for setup questions and ideas
once it is enabled; keep reproducible defects in Issues.

## Security and analysis

Enable these settings for the public repository:

- dependency graph and Dependabot alerts;
- Dependabot security updates;
- secret scanning and push protection;
- private vulnerability reporting;
- CodeQL code scanning via `.github/workflows/codeql.yml`.

## Main branch ruleset

Protect `main` with:

- pull requests required before merge;
- at least one approving review;
- dismiss stale approvals after new commits;
- conversation resolution required;
- required checks: `Build mod (all versions)`, `MCP server (typecheck + build)`,
  `Analyze (java-kotlin)`, and `Analyze (javascript-typescript)`;
- linear history and deletion protection;
- no force pushes and no bypass except emergency maintainers.

Prefer squash merges with Conventional Commit-style PR titles so generated release notes remain
readable.
