/**
 * The agent runtime: long-term state (memory, goals, chunk map) plus the body and job manager,
 * shared by every MCP session of this server process.
 */
import { homedir } from "node:os";
import { join } from "node:path";

import { BridgeError } from "../bridge.js";
import { Body, type Bridge, type ScanResult, type SelfState } from "./body.js";
import { KeyValue, openDatabase, type Database } from "./db.js";
import type { Located } from "./format.js";
import { GoalStore } from "./goals.js";
import { JobManager, type Job } from "./jobs.js";
import { MemoryStore } from "./memory.js";
import { ingestScan, type ObserveReport } from "./observe.js";
import { WorldMap } from "./worldmap.js";

export interface RuntimeOptions {
  /** SQLite file path, or ":memory:". */
  dbPath: string;
  /** Force a world id instead of asking the game (MCPFABRIC_WORLD). */
  worldOverride?: string;
}

export function defaultDbPath(env: NodeJS.ProcessEnv = process.env): string {
  const dir = env.MCPFABRIC_DATA_DIR || join(homedir(), ".mcpfabric");
  return join(dir, "agent.db");
}

const WORLD_TTL_MS = 15_000;

export class AgentRuntime {
  readonly db: Database;
  readonly memory: MemoryStore;
  readonly goals: GoalStore;
  readonly map: WorldMap;
  readonly kv: KeyValue;
  readonly body: Body;
  readonly jobs: JobManager;
  private worldCache?: { id: string; at: number };
  /** The container the runtime opened last, so transfers can refresh its memory. */
  openContainer?: { pos: Located; blockId: string };

  constructor(bridge: Bridge, private readonly opts: RuntimeOptions) {
    this.db = openDatabase(opts.dbPath);
    this.memory = new MemoryStore(this.db);
    this.goals = new GoalStore(this.db);
    this.map = new WorldMap(this.db);
    this.kv = new KeyValue(this.db);
    this.body = new Body(bridge);
    this.jobs = new JobManager(
      (job) => this.recordJob(job),
      async () => {
        // Release the body whatever the job was doing: walking or mining.
        await this.body.navStop().catch(() => undefined);
        await this.body.stopBreaking().catch(() => undefined);
      },
    );
  }

  /** Id of the world being played; all memory is scoped to it. */
  async world(): Promise<string> {
    if (this.opts.worldOverride) return this.opts.worldOverride;
    const now = Date.now();
    if (this.worldCache && now - this.worldCache.at < WORLD_TTL_MS) return this.worldCache.id;
    try {
      const info = await this.body.session();
      this.worldCache = { id: info.worldId, at: now };
    } catch (err) {
      if (err instanceof BridgeError && err.code === "unknown_method") {
        this.worldCache = { id: "default", at: now }; // mod without session.info
      } else if (this.worldCache) {
        return this.worldCache.id; // bridge hiccup: keep using the last world
      } else {
        throw err;
      }
    }
    return this.worldCache.id;
  }

  /** Forget the cached world id (e.g. after the player switched worlds). */
  resetWorld(): void {
    this.worldCache = undefined;
  }

  async here(): Promise<{ self: SelfState; at: Located }> {
    const self = await this.body.self();
    return { self, at: { dim: self.dimension, x: self.x, y: self.y, z: self.z } };
  }

  /** Scan the loaded area and fold it into the map and memory. */
  async observe(opts: { radius?: number; find?: string[]; findLimit?: number } = {}): Promise<{
    world: string;
    scan: ScanResult;
    report: ObserveReport;
    at: Located;
  }> {
    const world = await this.world();
    const scan = await this.body.scan(opts);
    const report = ingestScan(world, scan, this.map, this.memory);
    const at: Located = { dim: scan.dimension, ...scan.player };
    return { world, scan, report, at };
  }

  /** Episodic log: every finished job becomes an event memory, so failures inform later plans. */
  private recordJob(job: Job): void {
    const world = this.worldCache?.id ?? this.opts.worldOverride;
    if (!world) return;
    this.memory.add(world, {
      kind: "event",
      title: `${job.kind} ${job.state}: ${job.summary}`,
      body: job.result ?? "",
      tags: ["job", job.kind, job.state],
      importance: job.state === "failed" ? 0.5 : 0.3,
    });
  }

  close(): void {
    this.db.close();
  }
}
