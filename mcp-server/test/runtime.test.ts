import assert from "node:assert/strict";
import { test } from "node:test";
import { z } from "zod";

import { JobManager } from "../src/agent/jobs.js";
import { AgentRuntime } from "../src/agent/runtime.js";
import { AGENT_TOOLS, AgentSession } from "../src/agent/tools.js";
import { FakeWorld, vanillaRecipes } from "./fake-world.js";

function setup(world = new FakeWorld()) {
  const rt = new AgentRuntime(world, { dbPath: ":memory:" });
  const session = new AgentSession();
  const tool = (name: string, args: Record<string, unknown> = {}) => {
    const def = AGENT_TOOLS.find((t) => t.name === name);
    if (!def) throw new Error(`no tool ${name}`);
    // Parse like the MCP SDK does, so schema defaults apply and invalid schemas fail the test.
    return def.run(rt, session, z.object(def.inputSchema).parse(args));
  };
  return { world, rt, tool };
}

test("tool names are unique and do not clash with the bridge catalogue", async () => {
  const { TOOLS } = await import("../src/tools.js");
  const names = [...TOOLS.map((t) => t.name), ...AGENT_TOOLS.map((t) => t.name)];
  assert.equal(new Set(names).size, names.length);
});

test("observe records POIs, retires vanished ones and reports ores", async () => {
  const { world, tool } = setup();
  world.set({ x: 3, y: 64, z: 3 }, "minecraft:crafting_table").set({ x: 5, y: 64, z: 3 }, "minecraft:chest");
  world.set({ x: 10, y: 60, z: 10 }, "minecraft:iron_ore");
  const first = await tool("observe", { radius: 2 });
  assert.match(first, /25 chunks \(25 new\)/);
  assert.match(first, /new places: #1 crafting table .*#2 chest/);
  assert.match(first, /iron_ore×1/);

  world.blocks.delete("3,64,3");
  const second = await tool("observe", { radius: 2 });
  assert.match(second, /\(0 new\)/);
  assert.match(second, /gone \(no longer there\): #1 crafting table/);
  assert.doesNotMatch(second, /new places/);

  // A truncated scan must not retire what it did not list.
  const original = world.call.bind(world);
  world.call = (async (method: string, params?: Record<string, unknown>) => {
    const r = (await original(method, params)) as Record<string, unknown>;
    return method === "perception.scan" ? { ...r, pois: [], truncated: true } : r;
  }) as typeof world.call;
  assert.doesNotMatch(await tool("observe", { radius: 2 }), /gone/);
  world.call = original;

  const recall = await tool("recall", { query: "chest" });
  assert.match(recall, /#2 container "chest"/);
  const again = await tool("recall", { query: "chest" });
  assert.match(again, /already sent: #2/);
});

test("remember, recall by distance, verify and goals through the tools", async () => {
  const { world, tool } = setup();
  assert.match(await tool("remember", { kind: "place", title: "Home", tags: ["base"] }), /remembered #1 place "Home" @ 1 64 1/);
  assert.match(await tool("remember", { title: "Player wants a bridge", body: "across the river, oak" }), /#2 note/);
  const hits = await tool("recall", { query: "bridge river" });
  assert.match(hits, /already sent: #2/, "the session just stored it, so it is not repeated");
  assert.match(await tool("recall", { query: "bridge river", fresh: true }), /#2 note "Player wants a bridge" · seen just now\n {4}across the river, oak/);

  world.set({ x: 4, y: 64, z: 4 }, "minecraft:chest");
  await tool("observe", { radius: 1 });
  world.set({ x: 4, y: 64, z: 4 }, "minecraft:stone");
  const verify = await tool("memory_verify", {});
  assert.match(verify, /chest @ 4 64 4: changed \(now stone\)/);

  const g1 = await tool("goal_add", { title: "Iron kit", doneWhen: "iron pickaxe" });
  assert.match(g1, /added #1 \[pending\]/);
  await tool("goal_add", { title: "Collect logs", parent: 1, activate: true });
  const list = await tool("goal_list");
  assert.match(list, /#2 \[active\] Collect logs ◀ next/);
  const done = await tool("goal_update", { id: 2, status: "done", outcome: "8 logs" });
  assert.match(done, /all subgoals of #1 are closed/);

  const brief = await tool("agent_brief");
  assert.match(brief, /world sp:test/);
  assert.match(brief, /goal: #1 Iron kit/);
  assert.match(brief, /nearby memory:\n {2}#1 place "Home"/);
});

test("collect_blocks mines exposed ore with a pickaxe and refuses without one", async () => {
  const { world, rt, tool } = setup();
  world.set({ x: 6, y: 64, z: 2 }, "minecraft:iron_ore").set({ x: 9, y: 64, z: -3 }, "minecraft:iron_ore");
  const refused = await tool("collect_blocks", { blocks: ["iron_ore"], count: 2, waitSeconds: 20 });
  assert.match(refused, /\[failed\]/);
  assert.match(refused, /drops nothing without the right tool/);

  world.give("minecraft:stone_pickaxe", 1, 3);
  const ok = await tool("collect_blocks", { blocks: ["iron_ore"], count: 2, waitSeconds: 30 });
  assert.match(ok, /\[done\]/);
  assert.match(ok, /broke 2\/2 iron_ore; inventory \+raw_iron×2/);
  assert.equal(world.player.selected, 3, "switched to the pickaxe");
  const events = rt.memory.recall("sp:test", { kinds: ["event"] });
  assert.equal(events.length, 2, "both job outcomes were logged as events");
});

test("collect_blocks walks onto drops that are not at arm's length and fights off a zombie", async () => {
  const { world, tool } = setup();
  world.set({ x: 6, y: 64, z: 2 }, "minecraft:oak_log").set({ x: -5, y: 64, z: 7 }, "minecraft:oak_log");
  world.mobs.push({ uuid: "zombie-1", type: "minecraft:zombie", x: 2, y: 64, z: 1, health: 20 });
  world.mobs.push({ uuid: "creeper-1", type: "minecraft:creeper", x: 1, y: 64, z: 0, health: 20 });
  const out = await tool("collect_blocks", { blocks: ["oak_log"], count: 2, waitSeconds: 30 });
  assert.match(out, /\[done\][\s\S]*broke 2\/2 oak_log; inventory \+oak_log×2/);
  assert.equal(world.drops.length, 0, "every drop was picked up");
  assert.ok(world.calls.includes("attack:zombie-1"), "the zombie in reach was attacked");
  assert.ok(!world.calls.includes("attack:creeper-1"), "creepers are left alone");
});

test("craft_item crafts intermediates, makes and places a crafting table, and uses it", async () => {
  const world = new FakeWorld();
  world.recipes = vanillaRecipes();
  world.give("minecraft:oak_log", 2, 0);
  const { tool } = setup(world);
  const short = await tool("craft_item", { item: "wooden_pickaxe", waitSeconds: 30 });
  assert.match(short, /\[failed\][\s\S]*missing materials \(a crafting table is needed too\)/, "2 logs cannot cover table + pickaxe");

  world.give("minecraft:oak_log", 1, 0);
  const pick = await tool("craft_item", { item: "wooden_pickaxe", waitSeconds: 30 });
  assert.match(pick, /\[done\]/);
  assert.equal(world.count("minecraft:wooden_pickaxe"), 1);
  assert.equal(world.count("minecraft:oak_log"), 0);
  assert.ok([...world.blocks.values()].includes("minecraft:crafting_table"), "the table was crafted and placed");
  assert.equal(world.open, null, "the crafting screen was closed");

  const again = await tool("craft_item", { item: "stick", count: 4, waitSeconds: 30 });
  assert.match(again, /\[done\]/, "2×2 recipes work with no screen open");
});

test("plan_craft points at remembered storage for missing items", async () => {
  const world = new FakeWorld();
  world.recipes = vanillaRecipes();
  world.set({ x: 2, y: 64, z: 1 }, "minecraft:chest");
  world.containers.set("2,64,1", new Map([[0, { id: "minecraft:raw_iron", count: 5 }]]));
  const { tool } = setup(world);
  const opened = await tool("open_container", { x: 2, y: 64, z: 1 });
  assert.match(opened, /contents: raw_iron×5/);
  await tool("close_container");
  const planText = await tool("plan_craft", { item: "iron_pickaxe" });
  assert.match(planText, /gather: raw_iron×3/);
  assert.match(planText, /in remembered storage:\n  #1 chest @ 2 64 1: raw_iron×5/);
});

test("travel_to reaches a remembered place; unreachable targets fail cleanly", async () => {
  const { world, tool } = setup();
  await tool("remember", { kind: "place", title: "Far camp", x: 120, y: 64, z: -30 });
  const ok = await tool("travel_to", { place: 1, waitSeconds: 30 });
  assert.match(ok, /\[done\]/);
  assert.ok(Math.abs(world.player.x - 120) < 4);

  world.unreachable = new Set(Array.from({ length: 400 }, (_, i) => i).flatMap((i) => [`${i},64,0`]));
  const job = await tool("travel_to", { x: -200, z: 500, waitSeconds: 0 });
  assert.match(job, /\[running\]/);
  assert.match(await tool("job_cancel"), /cancelled job/);
});

test("job manager: one job at a time, cancel, wait", async () => {
  const jobs = new JobManager();
  const job = jobs.start("wait", "sleep", async (ctx) => {
    await ctx.sleep(10_000);
    return "slept";
  });
  assert.throws(() => jobs.start("other", "x", async () => "x"), /still running/);
  const waited = await jobs.wait(job.id, 50);
  assert.equal(waited!.state, "running");
  await jobs.cancel();
  assert.equal(jobs.get(job.id)!.state, "cancelled");
  const quick = jobs.start("quick", "q", async () => "ok");
  assert.equal((await jobs.wait(quick.id, 1000))!.result, "ok");
});
