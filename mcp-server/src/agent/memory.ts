/**
 * Long-term memory: notes, places, containers and events the agent has recorded, searchable with
 * SQLite FTS5 (BM25) and ranked together with recency, proximity and importance.
 *
 * Positional memories carry a `fingerprint` (the block id seen at that position) so they can be
 * re-verified against the live world, like index evidence: `valid`, `changed` or `gone`.
 */
import type { Database } from "./db.js";
import {
  distance,
  fmtDistance,
  fmtPos,
  fmtSeen,
  shortDim,
  truncate,
  type Located,
} from "./format.js";

/**
 * note: free-form knowledge · place: a location worth returning to · container: a storage block and
 * its last seen contents · event: something that happened (deaths, job outcomes) · skill: a
 * procedure that worked and is worth repeating.
 */
export const MEMORY_KINDS = ["note", "place", "container", "event", "skill"] as const;
export type MemoryKind = (typeof MEMORY_KINDS)[number];

export const MEMORY_STATUSES = ["valid", "changed", "gone", "unverified"] as const;
export type MemoryStatus = (typeof MEMORY_STATUSES)[number];

export interface Memory {
  id: number;
  world: string;
  kind: MemoryKind;
  title: string;
  body: string;
  tags: string[];
  pos?: Located;
  data?: Record<string, unknown>;
  fingerprint?: string;
  importance: number;
  status: MemoryStatus;
  /** Created by `observe` rather than by the model; such entries may be retired automatically. */
  auto: boolean;
  createdAt: number;
  updatedAt: number;
  observedAt: number;
}

export interface NewMemory {
  kind: MemoryKind;
  title: string;
  body?: string;
  tags?: string[];
  pos?: Located;
  data?: Record<string, unknown>;
  fingerprint?: string;
  importance?: number;
  auto?: boolean;
  status?: MemoryStatus;
}

export interface MemoryPatch {
  title?: string;
  body?: string;
  tags?: string[];
  pos?: Located;
  data?: Record<string, unknown>;
  fingerprint?: string;
  importance?: number;
  status?: MemoryStatus;
  /** Also refresh `observed_at` (the memory was just confirmed in the world). */
  observed?: boolean;
}

export interface RecallQuery {
  text?: string;
  kinds?: MemoryKind[];
  tags?: string[];
  /** Rank by distance from here (and filter by `radius` when given). */
  near?: Located;
  radius?: number;
  includeGone?: boolean;
  limit?: number;
}

export interface ScoredMemory {
  memory: Memory;
  score: number;
  /** Distance from `near` when both are in the same dimension. */
  distance?: number;
}

interface Row {
  id: number;
  world: string;
  kind: string;
  title: string;
  body: string;
  tags: string;
  dim: string | null;
  x: number | null;
  y: number | null;
  z: number | null;
  data: string | null;
  fingerprint: string | null;
  importance: number;
  status: string;
  auto: number;
  created_at: number;
  updated_at: number;
  observed_at: number;
  rank?: number;
}

function toMemory(r: Row): Memory {
  const m: Memory = {
    id: r.id,
    world: r.world,
    kind: r.kind as MemoryKind,
    title: r.title,
    body: r.body,
    tags: r.tags ? r.tags.split(" ").filter(Boolean) : [],
    importance: r.importance,
    status: r.status as MemoryStatus,
    auto: r.auto === 1,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
    observedAt: r.observed_at,
  };
  if (r.dim !== null && r.x !== null && r.y !== null && r.z !== null) {
    m.pos = { dim: r.dim, x: r.x, y: r.y, z: r.z };
  }
  if (r.data) m.data = JSON.parse(r.data) as Record<string, unknown>;
  if (r.fingerprint) m.fingerprint = r.fingerprint;
  return m;
}

function normTags(tags: string[] | undefined): string {
  if (!tags) return "";
  return [...new Set(tags.map((t) => t.trim().toLowerCase().replace(/\s+/g, "_")).filter(Boolean))].join(" ");
}

const STOP_WORDS = new Set([
  "minecraft", "the", "a", "an", "of", "in", "on", "at", "to", "is", "are", "where", "what", "my",
  "i", "me", "and", "or", "for", "with", "near", "any", "some",
]);

/**
 * Turn free text into a safe FTS5 query: every word becomes a quoted prefix term, OR-ed together so
 * BM25 ranks partial matches instead of dropping them. Returns null when nothing searchable is left.
 */
export function ftsQuery(text: string): string | null {
  const words = text.toLowerCase().match(/[\p{L}\p{N}]+/gu) ?? [];
  const terms = [...new Set(words)].filter((w) => !STOP_WORDS.has(w)).slice(0, 16);
  if (terms.length === 0) return null;
  return terms.map((t) => `"${t}"*`).join(" OR ");
}

const DAY_MS = 86_400_000;

export class MemoryStore {
  constructor(private readonly db: Database) {}

  add(world: string, m: NewMemory, now: number = Date.now()): Memory {
    const info = this.db
      .prepare(
        `INSERT INTO memories(world, kind, title, body, tags, dim, x, y, z, data, fingerprint, importance, status, auto,
                              created_at, updated_at, observed_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        world,
        m.kind,
        m.title.trim(),
        (m.body ?? "").trim(),
        normTags(m.tags),
        m.pos?.dim ?? null,
        m.pos ? Math.round(m.pos.x) : null,
        m.pos ? Math.round(m.pos.y) : null,
        m.pos ? Math.round(m.pos.z) : null,
        m.data ? JSON.stringify(m.data) : null,
        m.fingerprint ?? null,
        clamp01(m.importance ?? 0.5),
        m.status ?? "valid",
        m.auto ? 1 : 0,
        now,
        now,
        now,
      );
    return this.get(world, Number(info.lastInsertRowid))!;
  }

  get(world: string, id: number): Memory | undefined {
    const row = this.db.prepare("SELECT * FROM memories WHERE world = ? AND id = ?").get(world, id) as Row | undefined;
    return row ? toMemory(row) : undefined;
  }

  update(world: string, id: number, patch: MemoryPatch, now: number = Date.now()): Memory | undefined {
    const current = this.get(world, id);
    if (!current) return undefined;
    const sets: string[] = [];
    const args: (string | number | null)[] = [];
    const set = (col: string, value: string | number | null) => {
      sets.push(`${col} = ?`);
      args.push(value);
    };
    if (patch.title !== undefined) set("title", patch.title.trim());
    if (patch.body !== undefined) set("body", patch.body.trim());
    if (patch.tags !== undefined) set("tags", normTags(patch.tags));
    if (patch.pos !== undefined) {
      set("dim", patch.pos.dim);
      set("x", Math.round(patch.pos.x));
      set("y", Math.round(patch.pos.y));
      set("z", Math.round(patch.pos.z));
    }
    if (patch.data !== undefined) set("data", JSON.stringify(patch.data));
    if (patch.fingerprint !== undefined) set("fingerprint", patch.fingerprint);
    if (patch.importance !== undefined) set("importance", clamp01(patch.importance));
    if (patch.status !== undefined) set("status", patch.status);
    // `updated_at` versions the content; a pure re-observation only moves `observed_at`.
    const contentChanged = sets.length > 0 && changes(current, patch);
    if (contentChanged) set("updated_at", now);
    if (patch.observed) set("observed_at", now);
    if (sets.length === 0) return current;
    args.push(world, id);
    this.db.prepare(`UPDATE memories SET ${sets.join(", ")} WHERE world = ? AND id = ?`).run(...args);
    return this.get(world, id);
  }

  delete(world: string, id: number): boolean {
    return Number(this.db.prepare("DELETE FROM memories WHERE world = ? AND id = ?").run(world, id).changes) > 0;
  }

  /** The memory of one of `kinds` recorded at exactly this block position, if any. */
  findAt(world: string, pos: Located, kinds?: MemoryKind[]): Memory | undefined {
    const rows = this.db
      .prepare("SELECT * FROM memories WHERE world = ? AND dim = ? AND x = ? AND y = ? AND z = ? ORDER BY id")
      .all(world, pos.dim, Math.round(pos.x), Math.round(pos.y), Math.round(pos.z)) as unknown as Row[];
    const found = rows.map(toMemory).find((m) => !kinds || kinds.includes(m.kind));
    return found;
  }

  /** Positional memories inside a horizontal block box of one dimension. */
  inBox(world: string, dim: string, minX: number, maxX: number, minZ: number, maxZ: number): Memory[] {
    const rows = this.db
      .prepare("SELECT * FROM memories WHERE world = ? AND dim = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?")
      .all(world, dim, minX, maxX, minZ, maxZ) as unknown as Row[];
    return rows.map(toMemory);
  }

  count(world: string): number {
    const row = this.db.prepare("SELECT COUNT(*) AS n FROM memories WHERE world = ?").get(world) as { n: number };
    return row.n;
  }

  recall(world: string, q: RecallQuery, now: number = Date.now()): ScoredMemory[] {
    const limit = Math.max(1, Math.min(q.limit ?? 10, 100));
    const match = q.text ? ftsQuery(q.text) : null;
    const filters: string[] = ["m.world = ?"];
    const args: (string | number)[] = [world];
    if (q.kinds && q.kinds.length > 0) {
      filters.push(`m.kind IN (${q.kinds.map(() => "?").join(", ")})`);
      args.push(...q.kinds);
    }
    if (!q.includeGone) filters.push("m.status != 'gone'");
    if (q.near && q.radius !== undefined) {
      filters.push("m.dim = ? AND m.x BETWEEN ? AND ? AND m.z BETWEEN ? AND ?");
      args.push(q.near.dim, q.near.x - q.radius, q.near.x + q.radius, q.near.z - q.radius, q.near.z + q.radius);
    }

    let rows: Row[];
    if (match) {
      rows = this.db
        .prepare(
          `SELECT m.*, bm25(memories_fts, 4.0, 1.0, 2.0) AS rank
             FROM memories_fts JOIN memories m ON m.id = memories_fts.rowid
            WHERE memories_fts MATCH ? AND ${filters.join(" AND ")}
            ORDER BY rank LIMIT 300`,
        )
        .all(match, ...args) as unknown as Row[];
    } else if (q.text && q.text.trim()) {
      return []; // only stop words: nothing meaningful to match
    } else {
      rows = this.db
        .prepare(`SELECT m.* FROM memories m WHERE ${filters.join(" AND ")} ORDER BY m.observed_at DESC LIMIT 1000`)
        .all(...args) as unknown as Row[];
    }

    const wantTags = q.tags?.map((t) => t.toLowerCase());
    const bestRank = rows.reduce((min, r) => Math.min(min, r.rank ?? 0), 0);
    const scored: ScoredMemory[] = [];
    for (const row of rows) {
      const m = toMemory(row);
      if (wantTags && !wantTags.every((t) => m.tags.includes(t))) continue;
      let dist: number | undefined;
      if (q.near && m.pos && m.pos.dim === q.near.dim) {
        dist = distance(q.near, m.pos);
        if (q.radius !== undefined && dist > q.radius) continue;
      }
      const relevance = match && bestRank < 0 ? (row.rank ?? 0) / bestRank : 0;
      const recency = Math.exp(-(now - m.observedAt) / (7 * DAY_MS));
      const proximity = dist !== undefined ? Math.exp(-dist / 200) : 0;
      const penalty = m.status === "gone" ? 0.4 : m.status === "changed" ? 0.1 : 0;
      const score = match
        ? 0.55 * relevance + 0.15 * recency + 0.2 * proximity + 0.1 * m.importance - penalty
        : 0.45 * proximity + 0.3 * recency + 0.25 * m.importance - penalty;
      scored.push(dist === undefined ? { memory: m, score } : { memory: m, score, distance: dist });
    }
    scored.sort((a, b) => b.score - a.score);
    return scored.slice(0, limit);
  }
}

function clamp01(n: number): number {
  return Math.min(1, Math.max(0, n));
}

function changes(m: Memory, p: MemoryPatch): boolean {
  if (p.title !== undefined && p.title.trim() !== m.title) return true;
  if (p.body !== undefined && p.body.trim() !== m.body) return true;
  if (p.tags !== undefined && normTags(p.tags) !== m.tags.join(" ")) return true;
  if (p.status !== undefined && p.status !== m.status) return true;
  if (p.importance !== undefined && clamp01(p.importance) !== m.importance) return true;
  if (p.fingerprint !== undefined && p.fingerprint !== m.fingerprint) return true;
  if (p.data !== undefined && JSON.stringify(p.data) !== JSON.stringify(m.data)) return true;
  if (p.pos !== undefined) {
    const q = m.pos;
    if (!q || q.dim !== p.pos.dim || q.x !== Math.round(p.pos.x) || q.y !== Math.round(p.pos.y) || q.z !== Math.round(p.pos.z)) {
      return true;
    }
  }
  return false;
}

/**
 * Tracks which memory versions this MCP session already returned, so repeated recalls can refer
 * back to them (`already sent #3 #7`) instead of spending tokens on the same text again.
 */
export class SentLedger {
  private readonly sent = new Map<number, number>();

  has(m: Memory): boolean {
    return this.sent.get(m.id) === m.updatedAt;
  }

  mark(m: Memory): void {
    this.sent.set(m.id, m.updatedAt);
  }
}

/** One compact line (plus an optional indented body line) describing a memory. */
export function formatMemory(
  m: Memory,
  opts: { distance?: number; now?: number; full?: boolean; bodyChars?: number } = {},
): string {
  const now = opts.now ?? Date.now();
  const parts = [`#${m.id} ${m.kind} "${m.title}"`];
  if (m.pos) parts.push(`@ ${fmtPos(m.pos)} ${shortDim(m.pos.dim)}`);
  if (opts.distance !== undefined) parts.push(fmtDistance(opts.distance));
  parts.push(fmtSeen(m.observedAt, now));
  if (m.status !== "valid") parts.push(m.status.toUpperCase());
  if (m.tags.length > 0) parts.push(`[${m.tags.join(" ")}]`);
  let out = parts.join(" · ");
  if (m.body) {
    const body = opts.full ? m.body : truncate(m.body, opts.bodyChars ?? 160);
    out += `\n    ${body.replace(/\n/g, "\n    ")}`;
  }
  return out;
}
