/**
 * Typed access to the in-game bridge for the agent runtime: the "body" the runtime drives. Each
 * method maps to one bridge RPC; the shapes mirror the Java handlers in the mod.
 */
import type { Pos } from "./format.js";

/** The subset of `BridgeClient` the runtime needs (a fake implements it in tests). */
export interface Bridge {
  call<T = unknown>(method: string, params?: Record<string, unknown>): Promise<T>;
}

export interface SessionInfo {
  /** Stable id of the world / server, used to key all memory. */
  worldId: string;
  kind: "singleplayer" | "multiplayer" | "server";
  name: string;
  player?: string;
  dimension?: string;
}

export interface SelfState extends Pos {
  yaw: number;
  pitch: number;
  health: number;
  maxHealth: number;
  food: number;
  dimension: string;
  gameMode: string;
  onGround: boolean;
  inWater: boolean;
  selectedSlot: number;
}

export interface InvItem {
  slot: number;
  id: string;
  count: number;
  name?: string;
  damage?: number;
  maxDamage?: number;
}

export interface Inventory {
  selectedSlot: number;
  hotbar: InvItem[];
  main: InvItem[];
}

export interface ScanPoi extends Pos {
  id: string;
  /** The block entity holds items (chest, barrel, furnace, modded machine...). */
  container?: boolean;
}

export interface ScanResult {
  dimension: string;
  player: Pos;
  radius: number;
  chunks: { cx: number; cz: number; y?: number; biome?: string; counts?: Record<string, number> }[];
  pois: ScanPoi[];
  found?: (Pos & { id: string; exposed?: boolean })[];
  truncated?: boolean;
}

export interface BlockProbe extends Pos {
  loaded: boolean;
  id?: string;
  air?: boolean;
  requiresTool?: boolean;
  hardness?: number;
  /** Best hotbar slot (0-8) to mine this block with, when requested. */
  bestSlot?: number;
  /** Whether the best hotbar tool (or bare hand) actually gets drops from this block. */
  canHarvest?: boolean;
}

export interface NearbyEntity extends Pos {
  uuid: string;
  type: string;
  kind: "item" | "player" | "hostile" | "passive" | "other";
  distance: number;
  health?: number;
  item?: { id: string; count: number };
}

export interface NavStatus {
  active: boolean;
  state: string;
  target?: Pos;
  remainingNodes?: number;
  distance?: number;
}

export interface SlotItem {
  slot: number;
  id: string;
  count: number;
}

export interface ContainerState {
  open: boolean;
  containerId: number;
  type?: string;
  title?: string;
  /** Number of menu slots that belong to the container (not the player inventory). */
  size: number;
  slots: SlotItem[];
}

export interface Recipe {
  /** Opaque reference for `craft.place` (only meaningful while the session lasts). */
  ref: string;
  /** The player has unlocked it, so the recipe book can place it. */
  known: boolean;
  type: string;
  result: { id: string; count: number };
  /** One entry per filled slot, each listing the item ids accepted there. */
  ingredients: string[][];
  width?: number;
  height?: number;
  station?: string;
}

export class Body {
  constructor(private readonly bridge: Bridge) {}

  session(): Promise<SessionInfo> {
    return this.bridge.call("session.info");
  }

  self(): Promise<SelfState> {
    return this.bridge.call("player.getState");
  }

  inventory(): Promise<Inventory> {
    return this.bridge.call("player.getInventory");
  }

  /** Item id → total count over hotbar and main inventory. */
  async itemCounts(): Promise<Record<string, number>> {
    const inv = await this.inventory();
    const counts: Record<string, number> = {};
    for (const it of [...inv.hotbar, ...inv.main]) counts[it.id] = (counts[it.id] ?? 0) + it.count;
    return counts;
  }

  scan(opts: { radius?: number; find?: string[]; findLimit?: number } = {}): Promise<ScanResult> {
    return this.bridge.call("perception.scan", opts);
  }

  async blocks(positions: Pos[], opts: { tool?: boolean } = {}): Promise<BlockProbe[]> {
    const r = await this.bridge.call<{ blocks: BlockProbe[] }>("perception.blocks", { positions, ...opts });
    return r.blocks;
  }

  /** Entities around the player, nearest first; `kinds` filters (item, hostile, passive, player). */
  async entities(radius: number, kinds: NearbyEntity["kind"][] = []): Promise<NearbyEntity[]> {
    const r = await this.bridge.call<{ entities: NearbyEntity[] }>("perception.entities", { radius, kinds });
    return r.entities;
  }

  /** Attack an entity; ok is false when it was out of reach and nothing was sent. */
  attack(uuid: string): Promise<{ ok?: boolean }> {
    return this.bridge.call("interact.attackEntity", { uuid });
  }

  pathTo(target: Pos, opts: { reachRadius?: number; sprint?: boolean; timeoutSeconds?: number } = {}): Promise<{ pathLength: number }> {
    return this.bridge.call("nav.pathTo", { ...target, ...opts });
  }

  navStatus(): Promise<NavStatus> {
    return this.bridge.call("nav.status");
  }

  navStop(): Promise<unknown> {
    return this.bridge.call("nav.stop");
  }

  lookAt(p: Pos): Promise<unknown> {
    return this.bridge.call("control.lookAt", { ...p });
  }

  breakBlock(p: Pos): Promise<unknown> {
    return this.bridge.call("interact.breakBlock", { ...p, mode: "survival" });
  }

  stopBreaking(): Promise<unknown> {
    return this.bridge.call("interact.stopBreaking");
  }

  placeBlock(against: Pos, face: "up" | "down" | "north" | "south" | "east" | "west"): Promise<unknown> {
    return this.bridge.call("interact.placeBlock", { x: against.x, y: against.y, z: against.z, face });
  }

  selectHotbar(slot: number): Promise<unknown> {
    return this.bridge.call("inventory.selectHotbar", { slot });
  }

  /** Swap two player-inventory slots (0-8 hotbar, 9-35 main). */
  swapSlots(slotA: number, slotB: number): Promise<unknown> {
    return this.bridge.call("inventory.swapSlots", { slotA, slotB });
  }

  containerOpen(p: Pos): Promise<unknown> {
    return this.bridge.call("container.open", { ...p });
  }

  containerState(): Promise<ContainerState> {
    return this.bridge.call("container.state");
  }

  containerClick(slot: number, mode: "pickup" | "quick_move" | "throw", button = 0): Promise<unknown> {
    return this.bridge.call("container.click", { slot, mode, button });
  }

  containerTransfer(direction: "take" | "put", item?: string, count?: number): Promise<{ moved: Record<string, number> }> {
    return this.bridge.call("container.transfer", { direction, item, count });
  }

  containerClose(): Promise<unknown> {
    return this.bridge.call("container.close");
  }

  async recipes(items: string[], includeUnknown = true): Promise<Recipe[]> {
    const r = await this.bridge.call<{ recipes: Recipe[] }>("recipes.query", { items, includeUnknown });
    return r.recipes;
  }

  placeRecipe(ref: string, all: boolean): Promise<unknown> {
    return this.bridge.call("craft.place", { ref, all });
  }
}
