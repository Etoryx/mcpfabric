import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Runs against the compiled server: npm run build first (npm test does).
import { TOOLS } from "../dist/tools.js";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

function javaFiles(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return javaFiles(path);
    return entry.name.endsWith(".java") ? [path] : [];
  });
}

/** Bridge methods the mod registers (router.register("method", ...)) in src/. */
function registeredMethods() {
  const methods = new Set();
  for (const file of javaFiles(join(repoRoot, "src"))) {
    for (const match of readFileSync(file, "utf8").matchAll(/router\.register\("([^"]+)"/g)) methods.add(match[1]);
  }
  return methods;
}

test("every tool calls a bridge method the mod registers, and every method has a tool", () => {
  const registered = registeredMethods();
  const used = new Set(TOOLS.map((tool) => tool.method));
  assert.ok(registered.size > 0, "no router.register calls found under src/");
  assert.deepEqual([...used].filter((m) => !registered.has(m)).sort(), [], "tools calling a method the mod does not register");
  assert.deepEqual([...registered].filter((m) => !used.has(m)).sort(), [], "registered methods no tool exposes");
});
