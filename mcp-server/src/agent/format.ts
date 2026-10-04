/** Shared value types and compact text formatting for agent tool output. */

export interface Pos {
  x: number;
  y: number;
  z: number;
}

/** A position in a specific dimension. */
export interface Located extends Pos {
  dim: string;
}

export function distance(a: Pos, b: Pos): number {
  return Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
}

export function horizontalDistance(a: Pos, b: Pos): number {
  return Math.hypot(a.x - b.x, a.z - b.z);
}

/** `minecraft:iron_ore` → `iron_ore`; modded ids keep their namespace. */
export function shortId(id: string): string {
  return id.startsWith("minecraft:") ? id.slice("minecraft:".length) : id;
}

/** Accepts `iron_ore` or `minecraft:iron_ore` and returns the namespaced id. */
export function fullId(id: string): string {
  const trimmed = id.trim().toLowerCase();
  return trimmed.includes(":") ? trimmed : `minecraft:${trimmed}`;
}

export function shortDim(dim: string): string {
  return shortId(dim).replace(/^the_/, "");
}

export function fmtPos(p: Pos): string {
  return `${Math.round(p.x)} ${Math.round(p.y)} ${Math.round(p.z)}`;
}

export function fmtDistance(meters: number): string {
  return meters >= 10_000 ? `${(meters / 1000).toFixed(1)}km` : `${Math.round(meters)}m`;
}

/** Age of a timestamp relative to `now`: `now`, `40s`, `12m`, `3h`, `5d`. */
export function fmtAge(then: number, now: number = Date.now()): string {
  const s = Math.max(0, Math.round((now - then) / 1000));
  if (s < 5) return "now";
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.round(s / 60)}m`;
  if (s < 86_400) return `${Math.round(s / 3600)}h`;
  return `${Math.round(s / 86_400)}d`;
}

/** `seen just now` / `seen 12m ago`. */
export function fmtSeen(then: number, now: number = Date.now()): string {
  const age = fmtAge(then, now);
  return age === "now" ? "seen just now" : `seen ${age} ago`;
}

export function truncate(text: string, max: number): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length <= max ? flat : `${flat.slice(0, max - 1)}…`;
}

/** `{"minecraft:coal": 3, "create:zinc_ingot": 2}` → `coal×3 create:zinc_ingot×2`. */
export function fmtItems(items: Record<string, number>, limit = 24): string {
  const entries = Object.entries(items)
    .filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1]);
  const shown = entries.slice(0, limit).map(([id, n]) => `${shortId(id)}×${n}`);
  if (entries.length > limit) shown.push(`+${entries.length - limit} more`);
  return shown.join(" ");
}
