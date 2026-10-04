/**
 * Folds a perception scan into long-term state — the world-model equivalent of an incremental index
 * update: chunks go into the map, notable blocks become (or refresh) place/container memories, and
 * remembered blocks that are no longer there are marked `gone`.
 */
import type { ScanPoi, ScanResult } from "./body.js";
import { distance, fmtDistance, fmtPos, shortId, type Located } from "./format.js";
import type { Memory, MemoryStore } from "./memory.js";
import { chunkKey, type WorldMap } from "./worldmap.js";

interface PoiKind {
  kind: "place" | "container";
  tag: string;
  importance: number;
  /** Merge blocks of this type closer than this (portal frames, bed halves). */
  cluster: number;
}

const WORKSTATIONS = /(crafting_table|furnace|smoker|anvil|enchanting_table|smithing_table|stonecutter|brewing_stand|grindstone|loom|cartography_table|fletching_table|beacon|lectern|composter)$/;

/** How a scanned point of interest is remembered, or null when it is not worth a memory. */
export function classifyPoi(poi: ScanPoi): PoiKind | null {
  const id = shortId(poi.id);
  if (/nether_portal$|end_portal_frame$|end_gateway$/.test(id)) return { kind: "place", tag: "portal", importance: 0.8, cluster: 6 };
  if (/spawner$/.test(id)) return { kind: "place", tag: "spawner", importance: 0.7, cluster: 0 };
  if (/_bed$/.test(id)) return { kind: "place", tag: "bed", importance: 0.3, cluster: 1 };
  if (/ender_chest$/.test(id)) return { kind: "place", tag: "workstation", importance: 0.5, cluster: 0 };
  if (poi.container || /(chest|barrel|shulker_box)$/.test(id)) {
    // Furnace-likes hold items too, but they are workstations first. Rows of barrels or furnaces
    // are separate blocks (cluster 0); only the two halves of a double chest merge.
    if (WORKSTATIONS.test(id)) return { kind: "place", tag: "workstation", importance: 0.5, cluster: 0 };
    return { kind: "container", tag: "storage", importance: 0.6, cluster: /chest$/.test(id) ? 1 : 0 };
  }
  if (WORKSTATIONS.test(id)) return { kind: "place", tag: "workstation", importance: 0.5, cluster: 0 };
  return null;
}

export function poiTitle(id: string): string {
  return shortId(id).replace(/^.*:/, "").replace(/_/g, " ");
}

export interface ObserveReport {
  dim: string;
  chunks: number;
  newChunks: number;
  knownChunks: number;
  added: Memory[];
  restored: Memory[];
  gone: Memory[];
  /** Notable resource totals over the scanned area, id → count. */
  resources: Record<string, number>;
  /** Nearest chunk centre holding each rare resource. */
  nearestRare: Record<string, { x: number; z: number; distance: number }>;
}

const RESOURCE = /(_ore|ancient_debris|_log|_stem)$/;
const RARE = /(diamond_ore|emerald_ore|ancient_debris|gold_ore|lapis_ore|redstone_ore|iron_ore|copper_ore)$/;

export function ingestScan(
  world: string,
  scan: ScanResult,
  map: WorldMap,
  memory: MemoryStore,
  now: number = Date.now(),
): ObserveReport {
  const dim = scan.dimension;
  const { added: newChunks } = map.upsert(world, dim, scan.chunks, now);

  // --- points of interest -> memories -----------------------------------------------------
  const scanned = new Set(scan.chunks.map((c) => chunkKey(c.cx, c.cz)));
  const kept: { poi: ScanPoi; kind: PoiKind }[] = [];
  for (const poi of scan.pois) {
    const kind = classifyPoi(poi);
    if (!kind) continue;
    const dup = kept.find((k) => k.poi.id === poi.id && distance(k.poi, poi) <= k.kind.cluster);
    if (!dup) kept.push({ poi, kind });
  }

  const report: ObserveReport = {
    dim,
    chunks: scan.chunks.length,
    newChunks,
    knownChunks: map.count(world, dim),
    added: [],
    restored: [],
    gone: [],
    resources: {},
    nearestRare: {},
  };

  const xs = scan.chunks.map((c) => c.cx);
  const zs = scan.chunks.map((c) => c.cz);
  const existing = scan.chunks.length
    ? memory.inBox(world, dim, Math.min(...xs) * 16, Math.max(...xs) * 16 + 15, Math.min(...zs) * 16, Math.max(...zs) * 16 + 15)
    : [];
  const matched = new Set<number>();

  for (const { poi, kind } of kept) {
    const at: Located = { dim, x: poi.x, y: poi.y, z: poi.z };
    const hit = existing.find(
      (m) => m.data?.poi === true && m.fingerprint === poi.id && m.pos && distance(m.pos, at) <= kind.cluster,
    );
    if (hit) {
      matched.add(hit.id);
      const wasGone = hit.status === "gone";
      const updated = memory.update(world, hit.id, { status: "valid", observed: true }, now);
      if (wasGone && updated) report.restored.push(updated);
      continue;
    }
    const m = memory.add(
      world,
      {
        kind: kind.kind,
        title: poiTitle(poi.id),
        body: kind.kind === "container" ? "contents unknown (open_container to inspect)" : "",
        tags: ["auto", kind.tag],
        pos: at,
        data: { poi: true },
        fingerprint: poi.id,
        importance: kind.importance,
        auto: true,
      },
      now,
    );
    matched.add(m.id);
    report.added.push(m);
  }

  // Remembered POIs inside fully scanned chunks that were not seen this time are gone — unless the
  // POI list was truncated, in which case absence proves nothing.
  for (const m of scan.truncated ? [] : existing) {
    if (matched.has(m.id) || m.data?.poi !== true || !m.pos || m.status === "gone") continue;
    const key = chunkKey(Math.floor(m.pos.x / 16), Math.floor(m.pos.z / 16));
    if (!scanned.has(key)) continue;
    const updated = memory.update(world, m.id, { status: "gone" }, now);
    if (updated) report.gone.push(updated);
  }

  // --- resources ------------------------------------------------------------------------------
  for (const c of scan.chunks) {
    for (const [id, n] of Object.entries(c.counts ?? {})) {
      if (!RESOURCE.test(id)) continue;
      report.resources[id] = (report.resources[id] ?? 0) + n;
      if (RARE.test(id)) {
        const x = c.cx * 16 + 8;
        const z = c.cz * 16 + 8;
        const d = Math.hypot(x - scan.player.x, z - scan.player.z);
        const prev = report.nearestRare[id];
        if (!prev || d < prev.distance) report.nearestRare[id] = { x, z, distance: d };
      }
    }
  }
  return report;
}

export function formatObserveReport(r: ObserveReport, here: Located): string {
  const lines = [
    `observed ${r.chunks} chunks (${r.newChunks} new) around ${fmtPos(here)} ${shortId(r.dim)} · map knows ${r.knownChunks} chunks here`,
  ];
  const place = (m: Memory) => `#${m.id} ${m.title}${m.pos ? ` @ ${fmtPos(m.pos)} (${fmtDistance(distance(here, m.pos))})` : ""}`;
  if (r.added.length > 0) {
    const shown = r.added.slice(0, 12).map(place);
    if (r.added.length > 12) shown.push(`+${r.added.length - 12} more`);
    lines.push(`new places: ${shown.join(" · ")}`);
  }
  if (r.restored.length > 0) lines.push(`back again: ${r.restored.map(place).join(" · ")}`);
  if (r.gone.length > 0) lines.push(`gone (no longer there): ${r.gone.map(place).join(" · ")}`);

  const logs = Object.entries(r.resources).filter(([id]) => /_log$|_stem$/.test(id)).reduce((n, [, c]) => n + c, 0);
  const ores = Object.entries(r.resources)
    .filter(([id]) => !/_log$|_stem$/.test(id))
    .sort((a, b) => b[1] - a[1])
    .slice(0, 10)
    .map(([id, n]) => {
      const near = r.nearestRare[id];
      return near ? `${shortId(id)}×${n} (nearest ~${near.x} ${near.z}, ${fmtDistance(near.distance)})` : `${shortId(id)}×${n}`;
    });
  if (ores.length > 0) lines.push(`ores in view: ${ores.join(" · ")}`);
  if (logs > 0) lines.push(`wood: ${logs} log blocks`);
  return lines.join("\n");
}
