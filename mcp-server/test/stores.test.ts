import assert from "node:assert/strict";
import { test } from "node:test";

import { openDatabase } from "../src/agent/db.js";
import { formatGoalTree, GoalStore } from "../src/agent/goals.js";
import { ftsQuery, MemoryStore, SentLedger } from "../src/agent/memory.js";
import { renderMap, WorldMap, type ChunkScan } from "../src/agent/worldmap.js";

const W = "sp:test";
const here = { dim: "minecraft:overworld", x: 0, y: 64, z: 0 };

test("ftsQuery quotes terms, drops stop words and punctuation", () => {
  assert.equal(ftsQuery('where is my "iron"* ingot?'), '"iron"* OR "ingot"*');
  assert.equal(ftsQuery("the minecraft"), null);
  assert.equal(ftsQuery("алмазы у лавы"), '"алмазы"* OR "у"* OR "лавы"*');
});

test("memory recall ranks by text, then proximity; ledger dedups", () => {
  const mem = new MemoryStore(openDatabase(":memory:"));
  const far = mem.add(W, { kind: "place", title: "Iron vein", body: "lots of iron ore", pos: { ...here, x: 900 } });
  const near = mem.add(W, { kind: "place", title: "Iron cave", body: "iron ore near lava", pos: { ...here, x: 20 } });
  mem.add(W, { kind: "note", title: "Village", body: "trade emeralds" });
  mem.add("other-world", { kind: "note", title: "iron elsewhere" });

  const hits = mem.recall(W, { text: "iron", near: here });
  assert.deepEqual(hits.map((h) => h.memory.id), [near.id, far.id]);
  assert.ok(hits[0]!.distance! < 25);

  const withinRadius = mem.recall(W, { text: "iron", near: here, radius: 100 });
  assert.deepEqual(withinRadius.map((h) => h.memory.id), [near.id]);

  const ledger = new SentLedger();
  ledger.mark(near);
  assert.ok(ledger.has(near));
  const changed = mem.update(W, near.id, { body: "mined out" })!;
  assert.ok(!ledger.has(changed), "a content change makes the entry new again");
  const reobserved = mem.update(W, changed.id, { observed: true })!;
  assert.equal(reobserved.updatedAt, changed.updatedAt, "re-observing does not bump the version");
});

test("memory FTS follows updates and deletes; gone entries are hidden by default", () => {
  const mem = new MemoryStore(openDatabase(":memory:"));
  const m = mem.add(W, { kind: "container", title: "chest", body: "diamond×3", pos: here });
  assert.equal(mem.recall(W, { text: "diamond" }).length, 1);
  mem.update(W, m.id, { body: "cobblestone×64" });
  assert.equal(mem.recall(W, { text: "diamond" }).length, 0);
  assert.equal(mem.recall(W, { text: "cobblestone" }).length, 1);
  mem.update(W, m.id, { status: "gone" });
  assert.equal(mem.recall(W, { text: "cobblestone" }).length, 0);
  assert.equal(mem.recall(W, { text: "cobblestone", includeGone: true }).length, 1);
  assert.ok(mem.delete(W, m.id));
  assert.equal(mem.recall(W, { text: "cobblestone", includeGone: true }).length, 0);
});

test("goals: next is the first open leaf, active and higher priority first", () => {
  const goals = new GoalStore(openDatabase(":memory:"));
  const root = goals.add(W, { title: "Iron kit" });
  const wood = goals.add(W, { title: "Get wood", parentId: root.id });
  const stone = goals.add(W, { title: "Stone pickaxe", parentId: root.id });
  const iron = goals.add(W, { title: "Find iron", parentId: root.id, priority: 5 });
  assert.equal(goals.next(W)!.goal.id, iron.id);
  goals.update(W, wood.id, { status: "active" });
  const next = goals.next(W)!;
  assert.equal(next.goal.id, wood.id);
  assert.deepEqual(next.path.map((g) => g.id), [root.id]);
  goals.update(W, wood.id, { status: "done", outcome: "8 logs" });
  goals.update(W, iron.id, { status: "blocked" });
  assert.equal(goals.next(W)!.goal.id, stone.id);
  goals.update(W, stone.id, { status: "done" });
  // Only a blocked child left: the parent itself is not a leaf, so nothing is actionable.
  assert.equal(goals.next(W), undefined);
  assert.throws(() => goals.update(W, root.id, { parentId: iron.id }), /cycle/);
  const tree = formatGoalTree(goals.all(W));
  assert.match(tree, /#\d+ \[pending\] Iron kit/);
  assert.match(tree, /2 closed goals hidden/);
  assert.ok(goals.delete(W, root.id));
  assert.equal(goals.all(W).length, 0, "subgoals are deleted with their parent");
});

test("world map: frontier prefers the heading and avoids water; map renders", () => {
  const map = new WorldMap(openDatabase(":memory:"));
  const chunks: ChunkScan[] = [];
  for (let cx = -2; cx <= 2; cx++) for (let cz = -2; cz <= 2; cz++) chunks.push({ cx, cz, y: 64, biome: cx === -2 ? "minecraft:ocean" : "minecraft:plains", counts: cx === 1 && cz === 1 ? { "minecraft:diamond_ore": 2 } : {} });
  assert.equal(map.upsert(W, here.dim, chunks).added, 25);
  assert.equal(map.upsert(W, here.dim, chunks.slice(0, 3)).added, 0);

  const east = map.frontier(W, here.dim, { x: 8, y: 64, z: 8 }, { heading: { dx: 1, dz: 0 } });
  assert.equal(east[0]!.cx, 3, "heading east picks an eastern frontier");
  const west = map.frontier(W, here.dim, { x: 8, y: 64, z: 8 }, { heading: { dx: -1, dz: 0 } });
  assert.notEqual(west[0]!.cx, -3, "ocean frontier to the west is penalised");

  const found = map.findResources(W, here.dim, ["diamond"], here);
  assert.equal(found[0]!.chunk.cx, 1);
  assert.deepEqual(found[0]!.matches, { "minecraft:diamond_ore": 2 });

  const text = renderMap(map.around(W, here.dim, 0, 0, 3), { x: 8, y: 64, z: 8 }, 3, [{ x: 40, z: -24, glyph: "B", label: "base" }]);
  const rows = text.split("\n");
  assert.equal(rows[4], "?~.@..?", "row of the player's chunk: unexplored, ocean, land, you, land, land, unexplored");
  assert.equal(rows[2], "?~...B?", "marker drawn over its chunk");
  assert.equal(rows[5], "?~..$.?", "chunk with diamonds");
  assert.match(text, /B base/);
  assert.match(text, /\$ rare ores/);
});
