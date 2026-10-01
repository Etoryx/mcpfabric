/**
 * A tiny simulated Minecraft behind the bridge RPC contract, for testing the agent runtime without
 * a game: a flat world of blocks, an inventory, instant navigation, mining with drops, containers
 * and recipe-book crafting.
 */
import { BridgeError } from "../src/bridge.js";
import type { Bridge, Recipe } from "../src/agent/body.js";

type P = { x: number; y: number; z: number };
const key = (p: P) => `${Math.floor(p.x)},${Math.floor(p.y)},${Math.floor(p.z)}`;

const POI = /(chest|barrel|crafting_table|furnace|_bed|spawner|nether_portal)$/;
const DROPS: Record<string, string> = {
  "minecraft:iron_ore": "minecraft:raw_iron",
  "minecraft:stone": "minecraft:cobblestone",
  "minecraft:coal_ore": "minecraft:coal",
};
const HARD_NEEDS_PICK = /(stone|_ore)$/;

export class FakeWorld implements Bridge {
  blocks = new Map<string, string>();
  player = { x: 0.5, y: 64, z: 0.5, health: 20, dimension: "minecraft:overworld", selected: 0 };
  /** Inventory slot → stack (0-8 hotbar, 9-35 main). */
  slots = new Map<number, { id: string; count: number }>();
  containers = new Map<string, Map<number, { id: string; count: number }>>();
  recipes: Recipe[] = [];
  unreachable = new Set<string>();
  calls: string[] = [];
  worldId = "sp:test";
  navState = "idle";
  /** Dropped items lying in the world. */
  drops: { uuid: string; id: string; count: number; x: number; y: number; z: number }[] = [];
  /** Hostile mobs around the player. */
  mobs: { uuid: string; type: string; x: number; y: number; z: number; health: number }[] = [];
  private seq = 0;
  open: { type: string; pos?: P; grid?: { result: { id: string; count: number } | null } } | null = null;

  constructor(opts: { ground?: number; radius?: number } = {}) {
    const g = opts.ground ?? 63;
    const r = opts.radius ?? 40;
    for (let x = -r; x <= r; x++) for (let z = -r; z <= r; z++) this.blocks.set(key({ x, y: g, z }), "minecraft:grass_block");
  }

  set(p: P, id: string): this {
    this.blocks.set(key(p), id);
    return this;
  }

  give(id: string, count: number, slot?: number): this {
    const s = slot ?? this.freeSlot();
    const cur = this.slots.get(s);
    this.slots.set(s, { id, count: (cur?.id === id ? cur.count : 0) + count });
    return this;
  }

  count(id: string): number {
    let n = 0;
    for (const s of this.slots.values()) if (s.id === id) n += s.count;
    return n;
  }

  private freeSlot(): number {
    for (let i = 0; i < 36; i++) if (!this.slots.has(i)) return i;
    throw new Error("inventory full");
  }

  private addItem(id: string, n: number): void {
    for (const [slot, s] of this.slots) {
      if (s.id === id) {
        this.slots.set(slot, { id, count: s.count + n });
        return;
      }
    }
    this.slots.set(this.freeSlot(), { id, count: n });
  }

  private removeItem(id: string, n: number): boolean {
    if (this.count(id) < n) return false;
    for (const [slot, s] of [...this.slots]) {
      if (s.id !== id || n <= 0) continue;
      const used = Math.min(s.count, n);
      n -= used;
      if (s.count - used <= 0) this.slots.delete(slot);
      else this.slots.set(slot, { id, count: s.count - used });
    }
    return true;
  }

  private blockAt(p: P): string | undefined {
    return this.blocks.get(key(p));
  }

  private exposed(p: P): boolean {
    return [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]].some(
      ([dx, dy, dz]) => !this.blockAt({ x: p.x + dx!, y: p.y + dy!, z: p.z + dz! }),
    );
  }

  async call<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    this.calls.push(method);
    return this.handle(method, params) as T;
  }

  private handle(method: string, a: Record<string, any>): unknown {
    const p = this.player;
    switch (method) {
      case "session.info":
        return { worldId: this.worldId, kind: "singleplayer", name: "test" };
      case "player.getState":
        return { x: p.x, y: p.y, z: p.z, yaw: 0, pitch: 0, health: p.health, maxHealth: 20, food: 20, dimension: p.dimension, gameMode: "survival", onGround: true, inWater: false, selectedSlot: p.selected };
      case "player.getInventory": {
        const items = [...this.slots].map(([slot, s]) => ({ slot, ...s }));
        return { selectedSlot: p.selected, hotbar: items.filter((i) => i.slot < 9), main: items.filter((i) => i.slot >= 9) };
      }
      case "perception.scan": {
        const radius = a.radius ?? 6;
        const pcx = Math.floor(p.x / 16);
        const pcz = Math.floor(p.z / 16);
        const chunks = [];
        for (let cx = pcx - radius; cx <= pcx + radius; cx++) {
          for (let cz = pcz - radius; cz <= pcz + radius; cz++) {
            const counts: Record<string, number> = {};
            for (const [k, id] of this.blocks) {
              const [x, , z] = k.split(",").map(Number);
              if (Math.floor(x! / 16) === cx && Math.floor(z! / 16) === cz && /_ore$|_log$/.test(id)) counts[id] = (counts[id] ?? 0) + 1;
            }
            chunks.push({ cx, cz, y: 64, biome: "minecraft:plains", counts });
          }
        }
        const inArea = (x: number, z: number) => Math.abs(Math.floor(x / 16) - pcx) <= radius && Math.abs(Math.floor(z / 16) - pcz) <= radius;
        const pois = [];
        const found = [];
        const find: string[] = a.find ?? [];
        for (const [k, id] of this.blocks) {
          const [x, y, z] = k.split(",").map(Number) as [number, number, number];
          if (!inArea(x, z)) continue;
          if (POI.test(id)) pois.push({ id, x, y, z, ...(/chest|barrel/.test(id) ? { container: true } : {}) });
          if (find.includes(id)) found.push({ id, x, y, z, exposed: this.exposed({ x, y, z }) });
        }
        return { dimension: p.dimension, player: { x: p.x, y: p.y, z: p.z }, radius, chunks, pois, found };
      }
      case "perception.blocks": {
        const blocks = (a.positions as P[]).map((pos) => {
          const id = this.blockAt(pos);
          const out: Record<string, unknown> = { ...pos, loaded: true, air: !id, id: id ?? "minecraft:air" };
          if (a.tool && id) {
            const needs = HARD_NEEDS_PICK.test(id);
            const pickSlot = [...this.slots].find(([slot, s]) => slot < 9 && s.id.endsWith("_pickaxe"))?.[0];
            out.requiresTool = needs;
            out.bestSlot = needs && pickSlot !== undefined ? pickSlot : p.selected;
            out.canHarvest = !needs || pickSlot !== undefined;
            out.hardness = needs ? 3 : 2;
          }
          return out;
        });
        return { blocks };
      }
      case "nav.pathTo": {
        const target = { x: a.x, y: a.y, z: a.z };
        if (this.unreachable.has(key(target))) throw new BridgeError({ code: "not_found", message: "No path found" });
        // Arrive on the target when asked to get closer than a block, else next to it.
        const onTop = (a.reachRadius ?? 1) < 1;
        p.x = Math.floor(a.x) + (onTop ? 0.5 : 1.5);
        p.z = Math.floor(a.z) + 0.5;
        p.y = 64;
        this.navState = "reached";
        // Like vanilla: items are picked up only when the player is right on them.
        for (const d of [...this.drops]) {
          if (Math.hypot(d.x - p.x, d.z - p.z) <= 0.8) {
            this.addItem(d.id, d.count);
            this.drops.splice(this.drops.indexOf(d), 1);
          }
        }
        return { started: true, pathLength: 5 };
      }
      case "nav.status":
        return { active: false, state: this.navState };
      case "nav.stop":
        this.navState = "stopped";
        return "ok";
      case "perception.entities": {
        const kinds: string[] = a.kinds ?? [];
        const all = [
          ...this.drops.map((d) => ({ uuid: d.uuid, type: "minecraft:item", kind: "item", x: d.x, y: d.y, z: d.z, item: { id: d.id, count: d.count } })),
          ...this.mobs.map((m) => ({ uuid: m.uuid, type: m.type, kind: "hostile", x: m.x, y: m.y, z: m.z, health: m.health })),
        ]
          .map((e) => ({ ...e, distance: Math.hypot(e.x - p.x, e.y - p.y, e.z - p.z) }))
          .filter((e) => e.distance <= (a.radius ?? 16) && (kinds.length === 0 || kinds.includes(e.kind)))
          .sort((x, y) => x.distance - y.distance);
        return { entities: all };
      }
      case "interact.attackEntity": {
        const mob = this.mobs.find((m) => m.uuid === a.uuid);
        if (mob) {
          mob.health -= 7;
          if (mob.health <= 0) this.mobs.splice(this.mobs.indexOf(mob), 1);
        }
        this.calls.push(`attack:${a.uuid}`);
        return "ok";
      }
      case "control.lookAt":
      case "interact.stopBreaking":
        return "ok";
      case "inventory.selectHotbar":
        p.selected = a.slot;
        return "ok";
      case "inventory.swapSlots": {
        const sa = this.slots.get(a.slotA);
        const sb = this.slots.get(a.slotB);
        if (sb) this.slots.set(a.slotA, sb);
        else this.slots.delete(a.slotA);
        if (sa) this.slots.set(a.slotB, sa);
        else this.slots.delete(a.slotB);
        return "ok";
      }
      case "interact.breakBlock": {
        const id = this.blockAt(a as P);
        if (id) {
          this.blocks.delete(key(a as P));
          this.drops.push({ uuid: `drop-${++this.seq}`, id: DROPS[id] ?? id, count: 1, x: Math.floor(a.x) + 0.5, y: Math.floor(a.y), z: Math.floor(a.z) + 0.5 });
        }
        return { started: true };
      }
      case "interact.placeBlock": {
        const held = this.slots.get(p.selected);
        if (!held) return { result: "PASS" };
        const target = { x: a.x, y: a.y + 1, z: a.z };
        this.blocks.set(key(target), held.id);
        this.removeItem(held.id, 1);
        return { result: "SUCCESS" };
      }
      case "container.open": {
        const id = this.blockAt(a as P);
        if (id === "minecraft:crafting_table") this.open = { type: "minecraft:crafting", pos: a as P, grid: { result: null } };
        else if (id && /chest|barrel/.test(id)) this.open = { type: "minecraft:generic_9x3", pos: a as P };
        return { result: "SUCCESS" };
      }
      case "container.state": {
        if (!this.open) {
          const grid = this.inventoryGrid;
          return { open: false, containerId: 0, size: 5, slots: grid?.result ? [{ slot: 0, ...grid.result }] : [] };
        }
        if (this.open.type === "minecraft:crafting") {
          const r = this.open.grid?.result;
          return { open: true, containerId: 1, type: this.open.type, size: 10, slots: r ? [{ slot: 0, ...r }] : [] };
        }
        const items = this.containers.get(key(this.open.pos!)) ?? new Map();
        return { open: true, containerId: 1, type: this.open.type, size: 27, slots: [...items].map(([slot, s]) => ({ slot, ...s })) };
      }
      case "container.close":
        this.open = null;
        return "ok";
      case "container.transfer": {
        const moved: Record<string, number> = {};
        if (this.open?.pos && a.direction === "take") {
          const items = this.containers.get(key(this.open.pos)) ?? new Map();
          for (const [slot, s] of [...items]) {
            if (a.item && s.id !== a.item) continue;
            this.addItem(s.id, s.count);
            moved[s.id] = (moved[s.id] ?? 0) + s.count;
            items.delete(slot);
          }
        }
        return { moved };
      }
      case "recipes.query": {
        const want = new Set(a.items as string[]);
        return { source: "fake", recipes: this.recipes.filter((r) => want.has(r.result.id)) };
      }
      case "craft.place": {
        const recipe = this.recipes.find((r) => r.ref === a.ref);
        if (!recipe) throw new BridgeError({ code: "not_found", message: "unknown recipe" });
        const table = this.open?.type === "minecraft:crafting";
        const big = (recipe.width ?? 0) > 2 || (recipe.height ?? 0) > 2;
        if (big && !table) return { placed: false };
        const need: Record<string, number> = {};
        for (const alts of recipe.ingredients) {
          const pick = alts.find((id) => this.count(id) > (need[id] ?? 0)) ?? alts[0]!;
          need[pick] = (need[pick] ?? 0) + 1;
        }
        let times = 0;
        const max = a.all ? 64 : 1;
        while (times < max && Object.entries(need).every(([id, n]) => this.count(id) >= n)) {
          for (const [id, n] of Object.entries(need)) this.removeItem(id, n);
          times++;
        }
        if (times === 0) return { placed: false };
        const result = { id: recipe.result.id, count: recipe.result.count * times };
        if (table) this.open!.grid = { result };
        else this.inventoryGrid = { result };
        return { placed: true };
      }
      case "container.click": {
        if (a.slot === 0 && a.mode === "quick_move") {
          const grid = this.open?.type === "minecraft:crafting" ? this.open.grid : this.inventoryGrid;
          if (grid?.result) {
            this.addItem(grid.result.id, grid.result.count);
            grid.result = null;
          }
        }
        return "ok";
      }
      default:
        throw new BridgeError({ code: "unknown_method", message: `fake: ${method}` });
    }
  }

  inventoryGrid: { result: { id: string; count: number } | null } | null = null;
}

/** Vanilla-like recipes for the planner and craft tests. */
export function vanillaRecipes(): Recipe[] {
  const planks = ["minecraft:oak_planks", "minecraft:birch_planks", "minecraft:spruce_planks"];
  return [
    { ref: "d:1", known: true, type: "crafting", result: { id: "minecraft:oak_planks", count: 4 }, ingredients: [["minecraft:oak_log"]], width: 1, height: 1 },
    { ref: "d:2", known: true, type: "crafting", result: { id: "minecraft:birch_planks", count: 4 }, ingredients: [["minecraft:birch_log"]], width: 1, height: 1 },
    { ref: "d:3", known: true, type: "crafting", result: { id: "minecraft:stick", count: 4 }, ingredients: [planks, planks], width: 1, height: 2 },
    { ref: "d:4", known: true, type: "crafting", result: { id: "minecraft:crafting_table", count: 1 }, ingredients: [planks, planks, planks, planks], width: 2, height: 2 },
    { ref: "d:5", known: true, type: "crafting", result: { id: "minecraft:wooden_pickaxe", count: 1 }, ingredients: [planks, planks, planks, ["minecraft:stick"], ["minecraft:stick"]], width: 3, height: 3 },
    { ref: "d:6", known: true, type: "crafting", result: { id: "minecraft:stone_pickaxe", count: 1 }, ingredients: [["minecraft:cobblestone"], ["minecraft:cobblestone"], ["minecraft:cobblestone"], ["minecraft:stick"], ["minecraft:stick"]], width: 3, height: 3 },
    { ref: "d:7", known: false, type: "crafting", result: { id: "minecraft:iron_pickaxe", count: 1 }, ingredients: [["minecraft:iron_ingot"], ["minecraft:iron_ingot"], ["minecraft:iron_ingot"], ["minecraft:stick"], ["minecraft:stick"]], width: 3, height: 3 },
    { ref: "d:8", known: true, type: "smelting", result: { id: "minecraft:iron_ingot", count: 1 }, ingredients: [["minecraft:raw_iron"]] },
    { ref: "d:9", known: true, type: "crafting", result: { id: "minecraft:iron_ingot", count: 9 }, ingredients: [["minecraft:iron_block"]], width: 1, height: 1 },
    { ref: "d:10", known: true, type: "crafting", result: { id: "minecraft:iron_block", count: 1 }, ingredients: Array.from({ length: 9 }, () => ["minecraft:iron_ingot"]), width: 3, height: 3 },
    { ref: "d:11", known: true, type: "crafting", result: { id: "minecraft:iron_ingot", count: 1 }, ingredients: Array.from({ length: 9 }, () => ["minecraft:iron_nugget"]), width: 3, height: 3 },
    { ref: "d:12", known: true, type: "crafting", result: { id: "minecraft:iron_nugget", count: 9 }, ingredients: [["minecraft:iron_ingot"]], width: 1, height: 1 },
  ];
}
