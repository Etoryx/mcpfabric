import assert from "node:assert/strict";
import { test } from "node:test";

import type { Recipe } from "../src/agent/body.js";
import { collectRecipes, formatPlan, plan } from "../src/agent/planner.js";
import { vanillaRecipes } from "./fake-world.js";

function index(recipes: Recipe[]): (id: string) => Recipe[] {
  return (id) => recipes.filter((r) => r.result.id === id);
}

test("wooden pickaxe from logs: planks, sticks, then the pickaxe on a table", () => {
  const p = plan("minecraft:wooden_pickaxe", 1, { "minecraft:oak_log": 3 }, index(vanillaRecipes()));
  assert.deepEqual(p.gather, {});
  const planks = p.steps.filter((s) => s.output.id === "minecraft:oak_planks").reduce((n, s) => n + s.output.count, 0);
  assert.ok(planks >= 5, "3 planks for the head + 2 for sticks");
  assert.equal(p.steps.at(-1)!.output.id, "minecraft:wooden_pickaxe");
  assert.ok(p.steps.at(-1)!.needsTable);
  assert.ok(p.steps.findIndex((s) => s.output.id === "minecraft:stick") < p.steps.length - 1);
  assert.equal(p.uses["minecraft:oak_log"], 2);
  assert.match(formatPlan(p), /craftable now from inventory/);
});

test("uses stock first and chooses the alternative that is in the inventory", () => {
  const p = plan("minecraft:stick", 4, { "minecraft:birch_planks": 2 }, index(vanillaRecipes()));
  assert.equal(p.steps.length, 1);
  assert.deepEqual(p.steps[0]!.inputs, { "minecraft:birch_planks": 2 });
  assert.deepEqual(p.gather, {});
});

test("missing materials become gather entries; smelting shows up as a step", () => {
  const p = plan("minecraft:iron_pickaxe", 1, { "minecraft:stick": 2 }, index(vanillaRecipes()));
  assert.deepEqual(p.gather, { "minecraft:raw_iron": 3 }, "raw iron is gathered, not crafted back from blocks or nuggets");
  assert.ok(p.steps.some((s) => s.kind === "smelt" && s.output.id === "minecraft:iron_ingot"));
  assert.deepEqual(p.locked, ["minecraft:iron_pickaxe"]);
  const text = formatPlan(p);
  assert.match(text, /needs materials first/);
  assert.match(text, /fuel: 3 smelts/);
});

test("knownOnly treats locked recipes as unavailable", () => {
  const p = plan("minecraft:iron_pickaxe", 1, {}, index(vanillaRecipes()), { knownOnly: true });
  assert.deepEqual(p.gather, { "minecraft:iron_pickaxe": 1 });
  assert.deepEqual(p.steps, []);
});

test("cycles (ingot ↔ block ↔ nugget) terminate", () => {
  const p = plan("minecraft:iron_block", 1, {}, index(vanillaRecipes()));
  assert.ok(p.steps.length > 0);
  assert.equal(p.gather["minecraft:raw_iron"], 9);
});

test("collectRecipes walks the graph breadth-first with one fetch per level", async () => {
  const all = vanillaRecipes();
  const fetches: string[][] = [];
  const cache = await collectRecipes(
    "minecraft:wooden_pickaxe",
    async (ids) => {
      fetches.push(ids);
      return all.filter((r) => ids.includes(r.result.id));
    },
    new Map(),
  );
  assert.ok(cache.get("minecraft:stick")!.length > 0);
  assert.ok(cache.has("minecraft:oak_planks"));
  assert.ok(fetches.length <= 4, `levels fetched: ${fetches.length}`);
});
