/**
 * Saving the user's config when several devx processes run at once.
 *
 * Every process keeps its own copy of ~/.devxrc.json in memory. Writing that copy back whole would
 * let the process that saves last overwrite what the others changed meanwhile (theme, API keys,
 * "Always" permissions, the default model...). So a save writes only what THIS process changed
 * since it last looked at the file (`base` -> `mine`) on top of what is on disk now (`disk`).
 */

type Plain = Record<string, any>;

const isPlain = (v: unknown): v is Plain => !!v && typeof v === 'object' && !Array.isArray(v);

function isPrimitiveList(v: unknown): v is Array<string | number | boolean> {
  return Array.isArray(v) && v.every((x) => x === null || ['string', 'number', 'boolean'].includes(typeof x));
}

/** Structural equality for JSON-like values. */
export function sameValue(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((x, i) => sameValue(x, b[i]));
  if (isPlain(a) && isPlain(b)) {
    const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
    for (const k of keys) if (!sameValue(a[k], b[k])) return false;
    return true;
  }
  return false;
}

/** A deep copy that is safe to keep as a baseline. */
export function cloneConfig<T>(value: T): T {
  return value === undefined ? value : JSON.parse(JSON.stringify(value));
}

/**
 * `disk` with the changes that `base` -> `mine` made applied to it.
 *  - a value that did not change is left as it is on disk (another process may have changed it);
 *  - objects (apiKeys, trustedProjects...) are merged key by key;
 *  - lists of plain values (the bash allowlist) get what this process added and lose what it removed.
 */
export function mergeConfigChanges(base: Plain, mine: Plain, disk: Plain): Plain {
  const result: Plain = { ...disk };
  const keys = new Set([...Object.keys(base), ...Object.keys(mine)]);
  for (const key of keys) {
    const b = base[key];
    const m = mine[key];
    if (sameValue(b, m)) continue;

    if (m === undefined) {
      delete result[key];
    } else if (isPlain(m) && (b === undefined || isPlain(b)) && (disk[key] === undefined || isPlain(disk[key]))) {
      result[key] = mergeConfigChanges(isPlain(b) ? b : {}, m, isPlain(disk[key]) ? disk[key] : {});
    } else if (isPrimitiveList(m) && (b === undefined || isPrimitiveList(b)) && (disk[key] === undefined || isPrimitiveList(disk[key]))) {
      const old = (b as unknown[] | undefined) ?? [];
      const now: unknown[] = m;
      const added = now.filter((x) => !old.includes(x));
      const removed = old.filter((x) => !now.includes(x));
      // Emptying a list ("/permissions clear") really empties it.
      result[key] = m.length === 0
        ? []
        : [...new Set([...((disk[key] as unknown[] | undefined) ?? []).filter((x) => !removed.includes(x)), ...added])];
    } else {
      result[key] = m;
    }
  }
  return result;
}
