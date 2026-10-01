/**
 * MCP tools of the agent runtime. Unlike the bridge catalogue (`../tools.ts`), these run in the
 * server: they read and write long-term memory and start background jobs. Output is compact text
 * built for model context — ids to cite, distances, ages — rather than raw JSON.
 */
import { z } from "zod";

import type { BlockProbe } from "./body.js";

import {
  distance,
  fmtDistance,
  fmtItems,
  fmtPos,
  fmtSeen,
  fullId,
  shortDim,
  shortId,
  type Located,
} from "./format.js";
import { formatGoalTree, GOAL_STATUSES, isOpen } from "./goals.js";
import { formatJob, JobFailure, type JobContext } from "./jobs.js";
import { formatMemory, MEMORY_KINDS, MEMORY_STATUSES, SentLedger, type Memory, type MemoryKind } from "./memory.js";
import { formatObserveReport } from "./observe.js";
import { collectRecipes, formatPlan, plan } from "./planner.js";
import type { AgentRuntime } from "./runtime.js";
import { collect, containerItems, craft, explore, openContainerAt, recordContainer, travel } from "./skills.js";
import { chunkOf, renderMap, type MapMarker } from "./worldmap.js";

/** Per-MCP-session state. */
export class AgentSession {
  readonly ledger = new SentLedger();
}

export interface AgentToolDef {
  name: string;
  title: string;
  description: string;
  inputSchema: z.ZodRawShape;
  annotations?: { readOnlyHint?: boolean; destructiveHint?: boolean; idempotentHint?: boolean };
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  run: (rt: AgentRuntime, session: AgentSession, args: any) => Promise<string>;
}

const READ = { readOnlyHint: true } as const;

const coords = {
  x: z.number().describe("X coordinate."),
  y: z.number().describe("Y coordinate."),
  z: z.number().describe("Z coordinate."),
};

const waitSeconds = z
  .number()
  .min(0)
  .max(50)
  .optional()
  .describe("Block up to this many seconds for the job to finish before returning (default 0: return at once).");

/** A context for one-shot helpers that run outside a job. */
function inlineContext(): JobContext {
  const abort = new AbortController();
  return {
    signal: abort.signal,
    log: () => {},
    progress: () => {},
    sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
    checkpoint: () => {},
  };
}

async function hereOrNull(rt: AgentRuntime): Promise<Located | null> {
  try {
    return (await rt.here()).at;
  } catch {
    return null;
  }
}

async function startJob(
  rt: AgentRuntime,
  kind: string,
  summary: string,
  wait: number | undefined,
  run: (ctx: JobContext) => Promise<string>,
): Promise<string> {
  const job = rt.jobs.start(kind, summary, run);
  const done = wait ? await rt.jobs.wait(job.id, wait * 1000) : job;
  const text = formatJob(done ?? job);
  return (done ?? job).state === "running" ? `${text}\n(poll job_status to follow it; job_cancel stops it)` : text;
}

function markersFor(memories: Memory[]): MapMarker[] {
  const markers: MapMarker[] = [];
  for (const m of memories) {
    if (!m.pos || m.status === "gone") continue;
    let glyph: string | undefined;
    let label = "";
    if (m.tags.includes("base") || m.tags.includes("home")) [glyph, label] = ["B", "base"];
    else if (m.kind === "event" && m.tags.includes("death")) [glyph, label] = ["!", "death"];
    else if (m.tags.includes("portal")) [glyph, label] = ["P", "portal"];
    else if (m.kind === "container") [glyph, label] = ["C", "storage"];
    else if (m.tags.includes("workstation")) [glyph, label] = ["W", "workstation"];
    else if (m.kind === "place" && !m.auto) [glyph, label] = ["+", "remembered place"];
    if (glyph) markers.push({ x: m.pos.x, z: m.pos.z, glyph, label });
  }
  const rank = "B!P+CW";
  return markers.sort((a, b) => rank.indexOf(a.glyph) - rank.indexOf(b.glyph));
}

export const AGENT_TOOLS: AgentToolDef[] = [
  // ===== orientation =========================================================================
  {
    name: "agent_brief",
    title: "Agent brief (resume here)",
    description:
      "One-call situation report for resuming work after a restart or context loss: world id, your position/vitals/inventory, the active goal path, the running job, remembered places nearby and a small map. Call this first in a new session.",
    inputSchema: {},
    annotations: READ,
    run: async (rt, session) => {
      const world = await rt.world();
      const { self, at } = await rt.here();
      const counts = await rt.body.itemCounts().catch(() => ({}));
      const lines = [
        `# brief · world ${world} · memory ${rt.memory.count(world)} entries · map ${rt.map.count(world)} chunks`,
        `you: ${fmtPos(at)} ${shortDim(at.dim)} · health ${self.health.toFixed(0)}/${self.maxHealth.toFixed(0)} · food ${self.food} · ${self.gameMode}`,
        `inventory: ${fmtItems(counts, 20) || "empty"}`,
      ];
      const next = rt.goals.next(world);
      const goals = rt.goals.all(world);
      if (next) {
        const path = [...next.path, next.goal].map((g) => `#${g.id} ${g.title}`).join(" › ");
        lines.push(`goal: ${path}${next.goal.doneWhen ? ` (done when: ${next.goal.doneWhen})` : ""} · ${goals.filter((g) => isOpen(g.status)).length} open`);
      } else {
        lines.push(goals.length ? "goals: all closed — add the next one with goal_add" : "goals: none yet — set one with goal_add");
      }
      const job = rt.jobs.running();
      if (job) lines.push(formatJob(job));
      const nearby = rt.memory.recall(world, { near: at, radius: 160, limit: 8, kinds: ["place", "container", "note", "skill"] });
      if (nearby.length > 0) {
        lines.push("nearby memory:");
        for (const s of nearby) {
          lines.push(`  ${formatMemory(s.memory, { ...(s.distance !== undefined ? { distance: s.distance } : {}), bodyChars: 90 }).replace(/\n/g, "\n  ")}`);
          session.ledger.mark(s.memory);
        }
      }
      const radius = 5;
      const { cx, cz } = chunkOf(at);
      const chunks = rt.map.around(world, at.dim, cx, cz, radius);
      if (chunks.size > 0) {
        const marks = markersFor(rt.memory.inBox(world, at.dim, at.x - radius * 16 - 16, at.x + radius * 16 + 16, at.z - radius * 16 - 16, at.z + radius * 16 + 16));
        lines.push(renderMap(chunks, at, radius, marks));
      } else {
        lines.push("map: nothing mapped here yet — run observe");
      }
      return lines.join("\n");
    },
  },
  {
    name: "observe",
    title: "Observe surroundings",
    description:
      "Scan the loaded chunks around you (client-side, works in multiplayer) and update long-term state: the chunk map, remembered workstations/containers/portals/beds (new ones are added, vanished ones marked gone) and ore/wood counts. Optionally locate specific blocks with `find`. Cheap; call it after moving somewhere new.",
    inputSchema: {
      radius: z.number().int().min(1).max(12).optional().default(6).describe("Scan radius in chunks (clamped to what is loaded)."),
      find: z.array(z.string()).optional().describe('Block ids to locate, e.g. ["iron_ore","deepslate_iron_ore"].'),
    },
    run: async (rt, _s, args: { radius: number; find?: string[] }) => {
      const find = args.find?.map(fullId);
      const { scan, report, at } = await rt.observe({ radius: args.radius, ...(find ? { find, findLimit: 24 } : {}) });
      let text = formatObserveReport(report, at);
      if (find) {
        const found = (scan.found ?? []).sort((a, b) => distance(a, at) - distance(b, at));
        text += found.length
          ? `\nfound: ${found.slice(0, 12).map((b) => `${shortId(b.id)} @ ${fmtPos(b)} (${fmtDistance(distance(b, at))}${b.exposed === false ? ", buried" : ""})`).join(" · ")}${found.length > 12 ? ` +${found.length - 12} more` : ""}`
          : `\nfound: no ${find.map(shortId).join("/")} in the scanned area`;
      }
      return text;
    },
  },
  {
    name: "map_view",
    title: "Show the explored map",
    description:
      "North-up ASCII map of explored chunks around you (1 character = 1 chunk) with remembered places overlaid: base, deaths, portals, storage, workstations, rare ores. Unexplored chunks show as '?'.",
    inputSchema: {
      radius: z.number().int().min(2).max(24).optional().default(10).describe("Radius in chunks."),
    },
    annotations: READ,
    run: async (rt, _s, args: { radius: number }) => {
      const world = await rt.world();
      const { at } = await rt.here();
      const { cx, cz } = chunkOf(at);
      const chunks = rt.map.around(world, at.dim, cx, cz, args.radius);
      const span = args.radius * 16 + 16;
      const marks = markersFor(rt.memory.inBox(world, at.dim, at.x - span, at.x + span, at.z - span, at.z + span));
      return renderMap(chunks, at, args.radius, marks);
    },
  },

  // ===== memory ==============================================================================
  {
    name: "remember",
    title: "Remember something",
    description:
      "Store a long-term memory for this world: a note (facts, player requests, plans), a place worth returning to (base, village, cave entrance — tag 'base' marks home on the map), an event, or a skill (a procedure that worked). Places default to your current position.",
    inputSchema: {
      kind: z.enum(MEMORY_KINDS).optional().default("note"),
      title: z.string().min(1).max(120),
      body: z.string().max(4000).optional().describe("Details."),
      tags: z.array(z.string()).optional().describe('Short tags, e.g. ["base"], ["danger","lava"].'),
      here: z.boolean().optional().describe("Attach your current position (default: true for places)."),
      x: z.number().optional(),
      y: z.number().optional(),
      z: z.number().optional(),
      dimension: z.string().optional(),
      importance: z.number().min(0).max(1).optional().describe("0..1, default 0.5."),
    },
    run: async (rt, session, args) => {
      const world = await rt.world();
      let pos: Located | undefined;
      if (args.x !== undefined && args.y !== undefined && args.z !== undefined) {
        const dim = args.dimension ? fullId(args.dimension) : (await rt.here()).at.dim;
        pos = { dim, x: args.x, y: args.y, z: args.z };
      } else if (args.here ?? args.kind === "place") {
        pos = (await rt.here()).at;
      }
      const m = rt.memory.add(world, {
        kind: args.kind as MemoryKind,
        title: args.title,
        ...(args.body ? { body: args.body } : {}),
        ...(args.tags ? { tags: args.tags } : {}),
        ...(pos ? { pos } : {}),
        ...(args.importance !== undefined ? { importance: args.importance } : {}),
      });
      session.ledger.mark(m);
      return `remembered #${m.id} ${m.kind} "${m.title}"${m.pos ? ` @ ${fmtPos(m.pos)} ${shortDim(m.pos.dim)}` : ""}`;
    },
  },
  {
    name: "recall",
    title: "Search memory",
    description:
      "Search long-term memory (full-text, BM25) ranked with proximity, recency and importance. Also reports chunks where matching ores/logs were counted and storage holding matching items. Without a query, lists what is remembered near you. Pass ids for full entries. Entries this session already received are listed as 'already sent' — pass fresh:true to repeat them.",
    inputSchema: {
      query: z.string().optional().describe('Free text, e.g. "iron", "village", "where did I leave diamonds".'),
      kind: z.enum(MEMORY_KINDS).optional(),
      tags: z.array(z.string()).optional(),
      radius: z.number().min(1).optional().describe("Only memories within this many blocks of you."),
      limit: z.number().int().min(1).max(50).optional().default(10),
      ids: z.array(z.number().int()).optional().describe("Fetch these entries in full."),
      includeGone: z.boolean().optional().describe("Include places marked gone."),
      fresh: z.boolean().optional().describe("Repeat entries already sent in this session."),
    },
    annotations: READ,
    run: async (rt, session, args) => {
      const world = await rt.world();
      const here = await hereOrNull(rt);
      if (args.ids?.length) {
        const out = (args.ids as number[]).map((id) => {
          const m = rt.memory.get(world, id);
          if (!m) return `#${id}: not found`;
          session.ledger.mark(m);
          const d = here && m.pos && m.pos.dim === here.dim ? distance(here, m.pos) : undefined;
          let text = formatMemory(m, { full: true, ...(d !== undefined ? { distance: d } : {}) });
          if (m.data && Object.keys(m.data).some((k) => k !== "poi")) text += `\n    data: ${JSON.stringify(m.data)}`;
          return text;
        });
        return out.join("\n");
      }
      const results = rt.memory.recall(world, {
        ...(args.query ? { text: args.query } : {}),
        ...(args.kind ? { kinds: [args.kind] } : {}),
        ...(args.tags ? { tags: args.tags } : {}),
        ...(here ? { near: here } : {}),
        ...(args.radius !== undefined ? { radius: args.radius } : {}),
        ...(args.includeGone ? { includeGone: true } : {}),
        limit: args.limit,
      });
      const lines = [`# recall${args.query ? ` "${args.query}"` : " (nearby)"} · ${results.length} hit${results.length === 1 ? "" : "s"}`];
      const skipped: number[] = [];
      for (const r of results) {
        if (!args.fresh && session.ledger.has(r.memory)) {
          skipped.push(r.memory.id);
          continue;
        }
        session.ledger.mark(r.memory);
        lines.push(formatMemory(r.memory, r.distance !== undefined ? { distance: r.distance } : {}));
      }
      if (skipped.length) lines.push(`already sent: ${skipped.map((id) => `#${id}`).join(" ")} (fresh:true repeats them)`);
      if (args.query && here) {
        const terms = (args.query.toLowerCase().match(/[a-z0-9_]+/g) ?? []).filter((t: string) => t.length >= 3);
        const res = rt.map.findResources(world, here.dim, terms, here, 5);
        if (res.length) {
          lines.push("counted in chunk scans:");
          for (const r of res) {
            lines.push(`  ${fmtItems(r.matches, 4)} in chunk ${r.chunk.cx},${r.chunk.cz} (~${r.chunk.cx * 16 + 8} ${r.chunk.cz * 16 + 8}, ${fmtDistance(r.distance)}, ${fmtSeen(r.chunk.seenAt)})`);
          }
        }
      }
      if (lines.length === 1) lines.push("nothing found");
      return lines.join("\n");
    },
  },
  {
    name: "memory_update",
    title: "Edit or delete a memory",
    description: "Correct, re-tag, re-rate or delete a memory by id (e.g. mark a note obsolete, tag a place 'base').",
    inputSchema: {
      id: z.number().int(),
      title: z.string().min(1).max(120).optional(),
      body: z.string().max(4000).optional(),
      tags: z.array(z.string()).optional(),
      importance: z.number().min(0).max(1).optional(),
      status: z.enum(MEMORY_STATUSES).optional(),
      delete: z.boolean().optional(),
    },
    run: async (rt, _s, args) => {
      const world = await rt.world();
      if (args.delete) return rt.memory.delete(world, args.id) ? `deleted #${args.id}` : `#${args.id} not found`;
      const m = rt.memory.update(world, args.id, {
        ...(args.title !== undefined ? { title: args.title } : {}),
        ...(args.body !== undefined ? { body: args.body } : {}),
        ...(args.tags !== undefined ? { tags: args.tags } : {}),
        ...(args.importance !== undefined ? { importance: args.importance } : {}),
        ...(args.status !== undefined ? { status: args.status } : {}),
      });
      return m ? `updated ${formatMemory(m)}` : `#${args.id} not found`;
    },
  },
  {
    name: "memory_verify",
    title: "Verify remembered places",
    description:
      "Check remembered positions against the live world (like re-validating cached evidence): each place with a recorded block is reported valid, changed or gone; unloaded ones cannot be checked from here. Defaults to everything within 96 blocks.",
    inputSchema: {
      ids: z.array(z.number().int()).optional(),
      radius: z.number().min(1).max(512).optional().default(96),
    },
    run: async (rt, _s, args) => {
      const world = await rt.world();
      const { at } = await rt.here();
      const targets = args.ids?.length
        ? (args.ids as number[]).map((id) => rt.memory.get(world, id)).filter((m): m is Memory => !!m)
        : rt.memory.inBox(world, at.dim, at.x - args.radius, at.x + args.radius, at.z - args.radius, at.z + args.radius);
      const checkable = targets.filter((m) => m.pos && m.fingerprint && m.pos.dim === at.dim);
      if (checkable.length === 0) return "nothing to verify (no remembered blocks here)";
      const probes: BlockProbe[] = [];
      for (let i = 0; i < checkable.length; i += 500) {
        probes.push(...(await rt.body.blocks(checkable.slice(i, i + 500).map((m) => m.pos!))));
      }
      const verdicts: string[] = [];
      checkable.forEach((m, i) => {
        const p = probes[i];
        let verdict: string;
        if (!p || !p.loaded) verdict = "unloaded";
        else if (p.id === m.fingerprint) {
          rt.memory.update(world, m.id, { status: "valid", observed: true });
          verdict = "valid";
        } else {
          const status = p.air ? "gone" : "changed";
          rt.memory.update(world, m.id, { status });
          verdict = `${status} (now ${shortId(p.id ?? "?")})`;
        }
        verdicts.push(`#${m.id} ${m.title} @ ${fmtPos(m.pos!)}: ${verdict}`);
      });
      const bad = verdicts.filter((v) => !/: (valid|unloaded)$/.test(v)).length;
      return [`# verify · ${checkable.length} checked · ${bad} invalid`, ...verdicts].join("\n");
    },
  },

  // ===== goals ===============================================================================
  {
    name: "goal_add",
    title: "Add a goal",
    description:
      "Add a goal or a subgoal (with parent) to the persistent goal tree. Break big goals into concrete subgoals with a checkable doneWhen. Goals persist across sessions.",
    inputSchema: {
      title: z.string().min(1).max(160),
      parent: z.number().int().optional().describe("Parent goal id for a subgoal."),
      detail: z.string().max(2000).optional(),
      doneWhen: z.string().max(300).optional().describe('Checkable completion condition, e.g. "iron_pickaxe in inventory".'),
      priority: z.number().int().min(-10).max(10).optional().describe("Higher runs first among siblings."),
      activate: z.boolean().optional().describe("Mark it active right away."),
    },
    run: async (rt, _s, args) => {
      const world = await rt.world();
      const g = rt.goals.add(world, {
        title: args.title,
        parentId: args.parent ?? null,
        ...(args.detail ? { detail: args.detail } : {}),
        ...(args.doneWhen ? { doneWhen: args.doneWhen } : {}),
        ...(args.priority !== undefined ? { priority: args.priority } : {}),
        ...(args.activate ? { status: "active" as const } : {}),
      });
      const next = rt.goals.next(world);
      return `added #${g.id} [${g.status}] ${g.title}${g.parentId ? ` under #${g.parentId}` : ""}${next ? `\nnext: #${next.goal.id} ${next.goal.title}` : ""}`;
    },
  },
  {
    name: "goal_update",
    title: "Update a goal",
    description:
      "Change a goal's status (pending/active/blocked/done/failed/dropped), record its outcome, edit it, move it under another parent, or delete it with its subgoals. Returns the next goal to work on.",
    inputSchema: {
      id: z.number().int(),
      status: z.enum(GOAL_STATUSES).optional(),
      outcome: z.string().max(1000).optional().describe("What happened / why it failed / what was learned."),
      title: z.string().min(1).max(160).optional(),
      detail: z.string().max(2000).optional(),
      doneWhen: z.string().max(300).optional(),
      priority: z.number().int().min(-10).max(10).optional(),
      parent: z.number().int().nullable().optional().describe("New parent id, or null to make it a root goal."),
      delete: z.boolean().optional(),
    },
    run: async (rt, _s, args) => {
      const world = await rt.world();
      if (args.delete) return rt.goals.delete(world, args.id) ? `deleted #${args.id} and its subgoals` : `#${args.id} not found`;
      const g = rt.goals.update(world, args.id, {
        ...(args.status !== undefined ? { status: args.status } : {}),
        ...(args.outcome !== undefined ? { outcome: args.outcome } : {}),
        ...(args.title !== undefined ? { title: args.title } : {}),
        ...(args.detail !== undefined ? { detail: args.detail } : {}),
        ...(args.doneWhen !== undefined ? { doneWhen: args.doneWhen } : {}),
        ...(args.priority !== undefined ? { priority: args.priority } : {}),
        ...(args.parent !== undefined ? { parentId: args.parent } : {}),
      });
      const lines = [`#${g.id} [${g.status}] ${g.title}`];
      if (g.parentId && !isOpen(g.status)) {
        const siblings = rt.goals.all(world).filter((s) => s.parentId === g.parentId);
        if (siblings.every((s) => !isOpen(s.status))) lines.push(`all subgoals of #${g.parentId} are closed — close #${g.parentId} or add more`);
      }
      const next = rt.goals.next(world);
      lines.push(next ? `next: #${next.goal.id} ${next.goal.title}` : "no open goals left");
      return lines.join("\n");
    },
  },
  {
    name: "goal_list",
    title: "List goals",
    description: "The persistent goal tree with statuses; the goal to work on next is marked. Closed goals are hidden unless all=true.",
    inputSchema: { all: z.boolean().optional() },
    annotations: READ,
    run: async (rt, _s, args) => {
      const world = await rt.world();
      const goals = rt.goals.all(world);
      if (goals.length === 0) return "no goals yet — add one with goal_add";
      const next = rt.goals.next(world);
      const tree = formatGoalTree(goals, { all: !!args.all, ...(next ? { nextId: next.goal.id } : {}) });
      const detail = next?.goal.detail ? `\nnext detail: ${next.goal.detail}` : "";
      return tree + detail;
    },
  },

  // ===== planning ============================================================================
  {
    name: "plan_craft",
    title: "Plan a craft",
    description:
      "Expand an item into craft/smelt steps over the game's own recipes (vanilla and modded), using your current inventory, and list the raw materials still missing — plus remembered storage that holds them. On 1.21.2+ multiplayer only unlocked recipes are visible; singleplayer sees all.",
    inputSchema: {
      item: z.string().describe('Item id, e.g. "iron_pickaxe" or "create:cogwheel".'),
      count: z.number().int().min(1).max(4096).optional().default(1),
      knownOnly: z.boolean().optional().describe("Only use recipes you have unlocked (what craft_item can execute)."),
    },
    annotations: READ,
    run: async (rt, _s, args) => {
      const world = await rt.world();
      const item = fullId(args.item);
      const cache = await collectRecipes(item, (ids) => rt.body.recipes(ids), new Map());
      if ((cache.get(item) ?? []).length === 0) return `no recipe produces ${shortId(item)} (it must be gathered, traded or looted)`;
      const inventory = await rt.body.itemCounts();
      const p = plan(item, args.count, inventory, (id) => cache.get(id) ?? [], { knownOnly: !!args.knownOnly });
      let text = formatPlan(p);
      const missing = Object.keys(p.gather);
      if (missing.length > 0) {
        const here = await hereOrNull(rt);
        const stores = rt.memory
          .recall(world, { kinds: ["container"], limit: 100, ...(here ? { near: here } : {}) })
          .map((s) => s.memory)
          .filter((m) => missing.some((id) => ((m.data?.items as Record<string, number> | undefined)?.[id] ?? 0) > 0));
        if (stores.length > 0) {
          text += "\nin remembered storage:";
          for (const m of stores.slice(0, 5)) {
            const items = m.data!.items as Record<string, number>;
            const have = Object.fromEntries(missing.filter((id) => items[id]).map((id) => [id, items[id]!]));
            text += `\n  #${m.id} ${m.title} @ ${m.pos ? fmtPos(m.pos) : "?"}: ${fmtItems(have)} (${fmtSeen(m.observedAt)})`;
          }
        }
      }
      return text;
    },
  },
  {
    name: "get_recipes",
    title: "Get recipes for an item",
    description: "Recipes that produce an item, as reported by the game (crafting, smelting, stonecutting, smithing; vanilla and modded), with ingredient alternatives and whether you have unlocked each one.",
    inputSchema: { item: z.string() },
    annotations: READ,
    run: async (rt, _s, args) => {
      const item = fullId(args.item);
      const recipes = (await rt.body.recipes([item])).filter((r) => r.result.id === item);
      if (recipes.length === 0) return `no recipes produce ${shortId(item)}`;
      return recipes
        .slice(0, 12)
        .map((r) => {
          const ing = r.ingredients.map((alts) => (alts.length > 3 ? `${alts.slice(0, 3).map(shortId).join("|")}|…` : alts.map(shortId).join("|")));
          const shape = r.width && r.height ? ` ${r.width}×${r.height}` : "";
          return `${r.type}${shape}${r.known ? "" : " (locked)"}: ${shortId(r.result.id)}×${r.result.count} ← ${ing.join(", ")}`;
        })
        .join("\n");
    },
  },
  {
    name: "explore_next",
    title: "Suggest where to explore",
    description:
      "Best unexplored frontier chunks from the map (nearest, keeping the previous exploring direction, avoiding water) with a walkable target for each. Does not move; use travel_to, or the explore job to walk there automatically.",
    inputSchema: { count: z.number().int().min(1).max(10).optional().default(3) },
    annotations: READ,
    run: async (rt, _s, args) => {
      const world = await rt.world();
      const { at } = await rt.here();
      const heading = rt.kv.get<{ dx: number; dz: number }>(world, `explore.heading.${at.dim}`);
      const frontier = rt.map.frontier(world, at.dim, at, heading ? { heading } : {});
      if (frontier.length === 0) return rt.map.count(world, at.dim) === 0 ? "the map is empty here — run observe first" : "no frontier within 32 chunks";
      return frontier
        .slice(0, args.count)
        .map((f) => {
          const dir = compass(f.target.x - at.x, f.target.z - at.z);
          return `chunk ${f.cx},${f.cz} → walk to ${fmtPos(f.target)} (${fmtDistance(distance(at, f.target))} ${dir}${f.via.biome ? `, via ${shortId(f.via.biome)}` : ""})`;
        })
        .join("\n");
    },
  },

  // ===== jobs ================================================================================
  {
    name: "travel_to",
    title: "Travel to a position (job)",
    description:
      "Background job: walk to a position (or a remembered place by id) in path-finding segments, routing around obstacles and stopping if health gets low. Longer range than navigate_to. Returns a job id at once.",
    inputSchema: {
      x: z.number().optional(),
      y: z.number().optional().describe("Defaults to the mapped surface height there, else your height."),
      z: z.number().optional(),
      place: z.number().int().optional().describe("Memory id of a place to go to instead of coordinates."),
      reach: z.number().min(0.5).max(16).optional().default(2),
      sprint: z.boolean().optional(),
      waitSeconds,
    },
    run: async (rt, _s, args) => {
      const world = await rt.world();
      let target: { x: number; y: number; z: number; dim?: string };
      let label: string;
      if (args.place !== undefined) {
        const m = rt.memory.get(world, args.place);
        if (!m?.pos) throw new Error(`#${args.place} is not a remembered place`);
        target = m.pos;
        label = `#${m.id} ${m.title}`;
      } else if (args.x !== undefined && args.z !== undefined) {
        let y = args.y;
        if (y === undefined) {
          const { at } = await rt.here();
          const { cx, cz } = chunkOf({ x: args.x, z: args.z });
          y = rt.map.get(world, at.dim, cx, cz)?.y ?? at.y;
        }
        target = { x: args.x, y: y!, z: args.z };
        label = fmtPos(target);
      } else {
        throw new Error("give x and z (and optionally y), or place");
      }
      return startJob(rt, "travel", `to ${label}`, args.waitSeconds, (ctx) =>
        travel(rt, ctx, target, { reach: args.reach, ...(args.sprint ? { sprint: true } : {}) }),
      );
    },
  },
  {
    name: "explore",
    title: "Explore (job)",
    description:
      "Background job: repeatedly walk to the best frontier of the map, observing as it goes, and report new chunks, places and ores. Unreachable frontiers are remembered and skipped.",
    inputSchema: {
      legs: z.number().int().min(1).max(20).optional().default(4).describe("How many frontier targets to visit."),
      waitSeconds,
    },
    run: async (rt, _s, args) =>
      startJob(rt, "explore", `${args.legs} legs`, args.waitSeconds, (ctx) => explore(rt, ctx, { legs: args.legs })),
  },
  {
    name: "collect_blocks",
    title: "Collect blocks (job)",
    description:
      "Background job: find the nearest exposed blocks of the given types, walk to them, pick the best hotbar tool, mine them and pick up the drops, until `count` are broken. Refuses blocks your tools cannot harvest. Does not tunnel to buried blocks.",
    inputSchema: {
      blocks: z.array(z.string()).min(1).describe('Block ids, e.g. ["oak_log","birch_log"] or ["iron_ore","deepslate_iron_ore"].'),
      count: z.number().int().min(1).max(256),
      radius: z.number().int().min(8).max(128).optional().default(48).describe("Search radius in blocks."),
      maxSeconds: z.number().int().min(10).max(1800).optional().default(300),
      waitSeconds,
    },
    run: async (rt, _s, args) =>
      startJob(rt, "collect", `${args.count}× ${(args.blocks as string[]).map((b) => shortId(fullId(b))).join("/")}`, args.waitSeconds, (ctx) =>
        collect(rt, ctx, { blocks: args.blocks, count: args.count, radius: args.radius, maxSeconds: args.maxSeconds }),
      ),
  },
  {
    name: "craft_item",
    title: "Craft an item (job)",
    description:
      "Background job: plan and craft an item including intermediates (planks → sticks → tool) with the recipe book, using the 2×2 grid or a crafting table (walks to a nearby one, or places one from the inventory). Fails with the plan if materials are missing or a smelting step is needed.",
    inputSchema: {
      item: z.string(),
      count: z.number().int().min(1).max(640).optional().default(1),
      waitSeconds,
    },
    run: async (rt, _s, args) =>
      startJob(rt, "craft", `${args.count}× ${shortId(fullId(args.item))}`, args.waitSeconds, (ctx) =>
        craft(rt, ctx, { item: args.item, count: args.count }),
      ),
  },
  {
    name: "job_status",
    title: "Job status",
    description: "Status, progress and log of the running (or last) job. Waits up to waitSeconds for it to finish first.",
    inputSchema: {
      id: z.number().int().optional(),
      waitSeconds: z.number().min(0).max(50).optional().default(20),
    },
    annotations: READ,
    run: async (rt, _s, args) => {
      const job = await rt.jobs.wait(args.id, args.waitSeconds * 1000);
      return job ? formatJob(job) : "no jobs yet";
    },
  },
  {
    name: "job_cancel",
    title: "Cancel the job",
    description: "Stop the running job and release movement.",
    inputSchema: { id: z.number().int().optional() },
    run: async (rt, _s, args) => {
      const job = await rt.jobs.cancel(args.id);
      return job ? `cancelled job #${job.id} (${job.kind})` : "no running job";
    },
  },

  // ===== containers ==========================================================================
  {
    name: "open_container",
    title: "Open a container",
    description:
      "Open the chest/barrel/furnace/crafting table/modded machine at a position within reach, list its contents and remember them (so recall and plan_craft know what is stored where). Leaves the screen open for container_transfer; close_container when done.",
    inputSchema: { ...coords },
    run: async (rt, _s, args) => {
      if (rt.jobs.running()) throw new Error("a job is running; wait for it or job_cancel it first");
      const world = await rt.world();
      const { at } = await rt.here();
      const pos: Located = { dim: at.dim, x: Math.floor(args.x), y: Math.floor(args.y), z: Math.floor(args.z) };
      try {
        const { state, probe } = await openContainerAt(rt, inlineContext(), pos);
        rt.openContainer = { pos, blockId: probe.id! };
        const m = recordContainer(rt, world, pos, probe.id!, state);
        const items = containerItems(state);
        return `opened ${shortId(probe.id!)} @ ${fmtPos(pos)} (${state.type ? shortId(state.type) : "menu"}, ${state.size} slots) · memory #${m.id}\ncontents: ${fmtItems(items, 60) || "empty"}`;
      } catch (err) {
        if (err instanceof JobFailure) return err.message;
        throw err;
      }
    },
  },
  {
    name: "container_transfer",
    title: "Move items in/out of the open container",
    description:
      "With a container open: take items from it into your inventory, or put items from your inventory into it (whole stacks, shift-click style). Filter by item id and stop after roughly `count` items. Updates the remembered contents.",
    inputSchema: {
      direction: z.enum(["take", "put"]),
      item: z.string().optional().describe("Only this item id (default: everything)."),
      count: z.number().int().min(1).optional().describe("Stop once at least this many were moved."),
    },
    run: async (rt, _s, args) => {
      const r = await rt.body.containerTransfer(args.direction, args.item ? fullId(args.item) : undefined, args.count);
      const state = await rt.body.containerState();
      let note = "";
      if (rt.openContainer && state.open) {
        const world = await rt.world();
        const m = recordContainer(rt, world, rt.openContainer.pos, rt.openContainer.blockId, state);
        note = ` · memory #${m.id} updated`;
      }
      return `${args.direction === "take" ? "took" : "put"} ${fmtItems(r.moved) || "nothing"}${note}\ncontainer now: ${fmtItems(containerItems(state), 60) || "empty"}`;
    },
  },
  {
    name: "close_container",
    title: "Close the open container",
    description: "Close the currently open container / crafting screen.",
    inputSchema: {},
    run: async (rt) => {
      await rt.body.containerClose();
      rt.openContainer = undefined;
      return "closed";
    },
  },
];

function compass(dx: number, dz: number): string {
  const deg = (Math.atan2(dx, -dz) * 180) / Math.PI;
  const names = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];
  return names[((Math.round(deg / 45) % 8) + 8) % 8]!;
}
