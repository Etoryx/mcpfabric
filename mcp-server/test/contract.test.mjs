import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Runs against the compiled server: npm run build first (npm test does).
import { TOOLS } from "../dist/tools.js";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

function filesEndingWith(dir, suffix) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return filesEndingWith(path, suffix);
    return entry.name.endsWith(suffix) ? [path] : [];
  });
}

/** Bridge methods the mod registers (router.register("method", ...)) in src/. */
function registeredMethods() {
  const methods = new Set();
  for (const file of filesEndingWith(join(repoRoot, "src"), ".java")) {
    for (const match of readFileSync(file, "utf8").matchAll(/router\.register\("([^"]+)"/g)) methods.add(match[1]);
  }
  return methods;
}

/** Bridge methods the agent runtime calls itself (bridge.call("method", ...)) in mcp-server/src/agent/. */
function agentMethods() {
  const methods = new Set();
  for (const file of filesEndingWith(join(repoRoot, "mcp-server", "src", "agent"), ".ts")) {
    for (const match of readFileSync(file, "utf8").matchAll(/bridge\.call(?:<[^>]*>)?\(\s*"([^"]+)"/g)) methods.add(match[1]);
  }
  return methods;
}

test("every tool calls a bridge method the mod registers, and every method is used", () => {
  const registered = registeredMethods();
  const agent = agentMethods();
  assert.ok(agent.size > 0, "no bridge.call calls found under mcp-server/src/agent/");
  const used = new Set([...TOOLS.map((tool) => tool.method), ...agent]);
  assert.ok(registered.size > 0, "no router.register calls found under src/");
  assert.deepEqual([...used].filter((m) => !registered.has(m)).sort(), [], "tools calling a method the mod does not register");
  assert.deepEqual([...registered].filter((m) => !used.has(m)).sort(), [], "registered methods no tool or agent skill uses");
});
