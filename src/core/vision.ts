import fsp from 'fs/promises';
import path from 'path';
import { fileURLToPath } from 'url';
import { NATIVE_PROVIDERS, devxDir, normalizeModelKey, readJsonFile } from './catalog-util.js';
import { ensureSecureDir } from './secure-file.js';

/**
 * Facts about models from the models.dev catalogue: whether a model reads images, and the size
 * of its context window. A snapshot ships with devx and `/effort refresh` downloads a fresh one.
 * A model the catalogue does not know about images is given the benefit of the doubt: the image is sent.
 */

export interface VisionFile {
  version: 1;
  generated: string;
  /** Normalised ids of models that accept images. */
  yes: string[];
  /** Normalised ids of models known to take text only. */
  no: string[];
  /** Context window in tokens by normalised model id. */
  context?: Record<string, number>;
}

/** Reduces the models.dev API response to two lists of model ids. */
export function slimVision(api: any, now: Date = new Date()): VisionFile {
  const yes = new Set<string>();
  const no = new Set<string>();
  const windows = new Map<string, Array<{ provider: string; tokens: number }>>();
  for (const [providerId, provider] of Object.entries<any>(api || {})) {
    for (const [id, model] of Object.entries<any>(provider?.models || {})) {
      const key = normalizeModelKey(id);
      if (!key) continue;
      const input = model?.modalities?.input;
      if (Array.isArray(input)) (input.includes('image') ? yes : no).add(key);
      const tokens = model?.limit?.context;
      if (typeof tokens === 'number' && tokens >= 1024) {
        const list = windows.get(key) ?? [];
        list.push({ provider: providerId, tokens });
        windows.set(key, list);
      }
    }
  }
  // Providers disagree sometimes (a host that strips images): one yes is enough.
  for (const key of yes) no.delete(key);
  return { version: 1, generated: now.toISOString().slice(0, 10), yes: [...yes].sort(), no: [...no].sort(), context: pickWindows(windows) };
}

/**
 * One window per model. Hosts that resell a model often cap it, so the maker's own number wins;
 * for a model nobody makes in the list, the value most providers agree on.
 */
function pickWindows(windows: Map<string, Array<{ provider: string; tokens: number }>>): Record<string, number> {
  const out: Record<string, number> = {};
  const rank = (p: string) => {
    const i = NATIVE_PROVIDERS.indexOf(p);
    return i === -1 ? NATIVE_PROVIDERS.length : i;
  };
  for (const [key, list] of [...windows.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
    const best = [...list].sort((a, b) => rank(a.provider) - rank(b.provider))[0];
    if (rank(best.provider) < NATIVE_PROVIDERS.length) {
      out[key] = best.tokens;
      continue;
    }
    const counts = new Map<number, number>();
    for (const w of list) counts.set(w.tokens, (counts.get(w.tokens) ?? 0) + 1);
    out[key] = [...counts.entries()].sort((a, b) => b[1] - a[1] || b[0] - a[0])[0][0];
  }
  return out;
}

export class VisionCatalog {
  private yes: Set<string>;
  private no: Set<string>;
  private windows: Record<string, number>;
  readonly size: number;

  constructor(file?: VisionFile | null) {
    const ok = !!file && file.version === 1;
    this.yes = new Set(ok ? file!.yes : []);
    this.no = new Set(ok ? file!.no : []);
    this.windows = ok ? (file!.context ?? {}) : {};
    this.size = this.yes.size + this.no.size;
  }

  /** The context window in tokens, or undefined when the catalogue does not know the model. */
  contextWindow(model: string): number | undefined {
    const key = normalizeModelKey(model);
    return key ? this.windows[key] : undefined;
  }

  /** true: reads images; false: text only; undefined: not known. */
  accepts(model: string): boolean | undefined {
    const key = normalizeModelKey(model);
    if (!key) return undefined;
    if (this.yes.has(key)) return true;
    if (this.no.has(key)) return false;
    return undefined;
  }
}

let bundled: VisionCatalog | null = null;
let fresher: VisionCatalog | null = null;

export function bundledVision(): VisionCatalog {
  if (bundled) return bundled;
  let file: VisionFile | null = null;
  try {
    const here = path.dirname(fileURLToPath(import.meta.url));
    file = readJsonFile<VisionFile>(path.join(here, 'vision-catalog.json'));
  } catch {}
  file ??= readJsonFile<VisionFile>(path.join(process.cwd(), 'src/core/vision-catalog.json'));
  bundled = new VisionCatalog(file);
  return bundled;
}

export function loadUserVision(dir: string = devxDir()): VisionCatalog | null {
  const file = readJsonFile<VisionFile>(path.join(dir, 'vision-catalog.json'));
  fresher = file ? new VisionCatalog(file) : null;
  return fresher;
}

/** For tests: replace the catalogues. */
export function setVisionCatalogs(shipped: VisionCatalog | null, downloaded: VisionCatalog | null = null): void {
  bundled = shipped;
  fresher = downloaded;
}

export async function storeVision(file: VisionFile, dir: string = devxDir()): Promise<void> {
  await ensureSecureDir(dir);
  const target = path.join(dir, 'vision-catalog.json');
  await fsp.writeFile(target + '.tmp', JSON.stringify(file), 'utf8');
  await fsp.rename(target + '.tmp', target);
  fresher = new VisionCatalog(file);
}

/** Can this model read images? undefined when nobody knows (then the image is sent). */
export function modelAcceptsImages(model: string): boolean | undefined {
  return fresher?.accepts(model) ?? bundledVision().accepts(model);
}

/** The context window of a model according to the catalogue (a downloaded one wins over the shipped one). */
export function catalogContextWindow(model: string): number | undefined {
  return fresher?.contextWindow(model) ?? bundledVision().contextWindow(model);
}
