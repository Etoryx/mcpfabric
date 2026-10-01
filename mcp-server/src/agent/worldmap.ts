/**
 * Chunk-level world map: which chunks the agent has seen, their terrain (surface height, biome) and
 * counts of notable blocks. Drives the ASCII map, frontier-based exploration and resource lookup.
 */
import { transaction, type Database } from "./db.js";
import { shortId, type Pos } from "./format.js";

export interface ChunkScan {
  cx: number;
  cz: number;
  /** Feet height of the topmost standable surface at the chunk centre. */
  y?: number;
  biome?: string;
  /** Notable block id → count within the chunk. */
  counts?: Record<string, number>;
}

export interface ChunkInfo {
  dim: string;
  cx: number;
  cz: number;
  y: number | null;
  biome: string | null;
  counts: Record<string, number>;
  firstSeen: number;
  seenAt: number;
}

export interface FrontierTarget {
  /** The unexplored chunk. */
  cx: number;
  cz: number;
  /** The known chunk next to it; walking to its centre reveals the frontier chunk. */
  via: ChunkInfo;
  /** Block position to walk to (centre of `via`). */
  target: Pos;
  score: number;
  distanceChunks: number;
}

export interface FrontierOptions {
  /** Search radius around `from`, in chunks. */
  radius?: number;
  /** Preferred direction of travel (unit-ish vector in the x/z plane). */
  heading?: { dx: number; dz: number };
  /** Penalise frontier cells reached through oceans and rivers. */
  avoidWater?: boolean;
}

interface Row {
  dim: string;
  cx: number;
  cz: number;
  surface_y: number | null;
  biome: string | null;
  counts: string;
  first_seen: number;
  seen_at: number;
}

function toChunk(r: Row): ChunkInfo {
  return {
    dim: r.dim,
    cx: r.cx,
    cz: r.cz,
    y: r.surface_y,
    biome: r.biome,
    counts: JSON.parse(r.counts) as Record<string, number>,
    firstSeen: r.first_seen,
    seenAt: r.seen_at,
  };
}

export const chunkOf = (p: { x: number; z: number }) => ({ cx: Math.floor(p.x / 16), cz: Math.floor(p.z / 16) });
export const chunkKey = (cx: number, cz: number) => `${cx},${cz}`;

export function isWaterBiome(biome: string | null | undefined): boolean {
  return !!biome && /ocean|river/.test(biome);
}

export class WorldMap {
  constructor(private readonly db: Database) {}

  /** Store a scan. Returns how many chunks were seen for the first time. */
  upsert(world: string, dim: string, chunks: ChunkScan[], now: number = Date.now()): { added: number } {
    const existing = this.db.prepare("SELECT 1 FROM chunks WHERE world = ? AND dim = ? AND cx = ? AND cz = ?");
    const upsert = this.db.prepare(
      `INSERT INTO chunks(world, dim, cx, cz, surface_y, biome, counts, first_seen, seen_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(world, dim, cx, cz) DO UPDATE SET
         surface_y = excluded.surface_y, biome = excluded.biome, counts = excluded.counts, seen_at = excluded.seen_at`,
    );
    let added = 0;
    transaction(this.db, () => {
      for (const c of chunks) {
        if (!existing.get(world, dim, c.cx, c.cz)) added++;
        upsert.run(world, dim, c.cx, c.cz, c.y ?? null, c.biome ?? null, JSON.stringify(c.counts ?? {}), now, now);
      }
    });
    return { added };
  }

  get(world: string, dim: string, cx: number, cz: number): ChunkInfo | undefined {
    const row = this.db
      .prepare("SELECT * FROM chunks WHERE world = ? AND dim = ? AND cx = ? AND cz = ?")
      .get(world, dim, cx, cz) as Row | undefined;
    return row ? toChunk(row) : undefined;
  }

  /** Known chunks within `radius` chunks (square) of a centre chunk, keyed by `chunkKey`. */
  around(world: string, dim: string, cx: number, cz: number, radius: number): Map<string, ChunkInfo> {
    const rows = this.db
      .prepare("SELECT * FROM chunks WHERE world = ? AND dim = ? AND cx BETWEEN ? AND ? AND cz BETWEEN ? AND ?")
      .all(world, dim, cx - radius, cx + radius, cz - radius, cz + radius) as unknown as Row[];
    const map = new Map<string, ChunkInfo>();
    for (const r of rows) map.set(chunkKey(r.cx, r.cz), toChunk(r));
    return map;
  }

  count(world: string, dim?: string): number {
    const row = (
      dim
        ? this.db.prepare("SELECT COUNT(*) AS n FROM chunks WHERE world = ? AND dim = ?").get(world, dim)
        : this.db.prepare("SELECT COUNT(*) AS n FROM chunks WHERE world = ?").get(world)
    ) as { n: number };
    return row.n;
  }

  /**
   * Unexplored chunks bordering explored ones, best first. The score is the distance in chunks plus
   * penalties for turning away from `heading` and for crossing water, so repeated calls sweep
   * outward in a steady direction instead of zig-zagging between the nearest gaps.
   */
  frontier(world: string, dim: string, from: Pos, opts: FrontierOptions = {}): FrontierTarget[] {
    const radius = opts.radius ?? 32;
    const { cx: pcx, cz: pcz } = chunkOf(from);
    const known = this.around(world, dim, pcx, pcz, radius + 1);
    const heading = normalise(opts.heading);
    const best = new Map<string, FrontierTarget>();
    for (const chunk of known.values()) {
      if (chunk.y === null) continue; // no standable surface known: cannot walk there
      for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]] as const) {
        const cx = chunk.cx + dx;
        const cz = chunk.cz + dz;
        const key = chunkKey(cx, cz);
        if (known.has(key)) continue;
        const ox = cx - pcx;
        const oz = cz - pcz;
        const dist = Math.hypot(ox, oz);
        if (dist > radius) continue;
        let score = dist;
        if (heading && dist > 0) {
          const cos = (ox * heading.dx + oz * heading.dz) / dist;
          score += (1 - cos) * 4; // up to +8 chunks for going backwards
        }
        if (opts.avoidWater !== false && isWaterBiome(chunk.biome)) score += 6;
        const prev = best.get(key);
        if (!prev || score < prev.score) {
          best.set(key, {
            cx,
            cz,
            via: chunk,
            target: { x: chunk.cx * 16 + 8, y: chunk.y, z: chunk.cz * 16 + 8 },
            score,
            distanceChunks: dist,
          });
        }
      }
    }
    return [...best.values()].sort((a, b) => a.score - b.score);
  }

  /**
   * Chunks whose block counts include an id matching one of `terms` (substring match on the id), with
   * the matched counts, nearest first.
   */
  findResources(world: string, dim: string, terms: string[], from: Pos, limit = 5): { chunk: ChunkInfo; matches: Record<string, number>; distance: number }[] {
    const clean = terms.map((t) => t.toLowerCase()).filter((t) => t.length >= 3);
    if (clean.length === 0) return [];
    const where = clean.map(() => "counts LIKE ?").join(" OR ");
    const rows = this.db
      .prepare(`SELECT * FROM chunks WHERE world = ? AND dim = ? AND (${where})`)
      .all(world, dim, ...clean.map((t) => `%${t}%`)) as unknown as Row[];
    const out = [];
    for (const row of rows) {
      const chunk = toChunk(row);
      const matches: Record<string, number> = {};
      for (const [id, n] of Object.entries(chunk.counts)) {
        if (clean.some((t) => id.includes(t))) matches[id] = n;
      }
      if (Object.keys(matches).length === 0) continue;
      const distance = Math.hypot(chunk.cx * 16 + 8 - from.x, chunk.cz * 16 + 8 - from.z);
      out.push({ chunk, matches, distance });
    }
    return out.sort((a, b) => a.distance - b.distance).slice(0, limit);
  }
}

function normalise(v: { dx: number; dz: number } | undefined): { dx: number; dz: number } | undefined {
  if (!v) return undefined;
  const len = Math.hypot(v.dx, v.dz);
  return len > 1e-6 ? { dx: v.dx / len, dz: v.dz / len } : undefined;
}

const RARE = /diamond|emerald|ancient_debris/;

/** Terrain glyph for a known chunk. */
export function terrainGlyph(c: ChunkInfo): string {
  const b = shortId(c.biome ?? "");
  if (isWaterBiome(b)) return "~";
  if (/peak|mountain|slope|windswept|stony|jagged/.test(b)) return "^";
  if (/snow|ice|frozen/.test(b)) return "*";
  if (/desert|badlands|savanna|beach/.test(b)) return ":";
  if (/forest|taiga|jungle|grove|swamp|mangrove|cherry|woodland/.test(b)) return "T";
  return ".";
}

export interface MapMarker {
  x: number;
  z: number;
  glyph: string;
  label: string;
}

/**
 * Render known chunks around `center` as a north-up ASCII grid (one character per chunk) with a
 * legend of the glyphs actually used. Markers are drawn over terrain; the first marker in a cell wins.
 */
export function renderMap(
  chunks: Map<string, ChunkInfo>,
  center: Pos,
  radius: number,
  markers: MapMarker[] = [],
): string {
  const { cx: pcx, cz: pcz } = chunkOf(center);
  const overlay = new Map<string, MapMarker>();
  for (const m of markers) {
    const { cx, cz } = chunkOf(m);
    const key = chunkKey(cx, cz);
    if (!overlay.has(key)) overlay.set(key, m);
  }
  const used = new Map<string, string>();
  const terrainNames: Record<string, string> = {
    "?": "unexplored",
    ".": "land",
    "~": "water",
    "^": "mountains",
    "*": "snow/ice",
    ":": "dry/sand",
    T: "forest",
    $: "rare ores",
  };
  const rows: string[] = [];
  for (let dz = -radius; dz <= radius; dz++) {
    let line = "";
    for (let dx = -radius; dx <= radius; dx++) {
      const cx = pcx + dx;
      const cz = pcz + dz;
      const key = chunkKey(cx, cz);
      let glyph: string;
      if (dx === 0 && dz === 0) {
        glyph = "@";
        used.set("@", "you");
      } else if (overlay.has(key)) {
        const m = overlay.get(key)!;
        glyph = m.glyph;
        used.set(glyph, m.label);
      } else {
        const chunk = chunks.get(key);
        if (!chunk) glyph = "?";
        else if (Object.keys(chunk.counts).some((id) => RARE.test(id))) glyph = "$";
        else glyph = terrainGlyph(chunk);
        used.set(glyph, terrainNames[glyph] ?? glyph);
      }
      line += glyph;
    }
    rows.push(line);
  }
  const legend = [...used.entries()].map(([g, name]) => `${g} ${name}`).join(" · ");
  const x0 = (pcx - radius) * 16;
  const z0 = (pcz - radius) * 16;
  return [
    `north ↑ · 1 char = 1 chunk (16×16) · top-left block ${x0} ${z0}`,
    ...rows,
    legend,
  ].join("\n");
}
