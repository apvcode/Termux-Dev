// Builds src/core/reasoning-catalog.json (the reasoning options) and src/core/price-catalog.json
// (the prices) of every model in the models.dev catalogue. Run after `npm run build`:  node scripts/build-reasoning-catalog.mjs
// The result is committed, so users never need to download anything to get the snapshot.
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { slimCatalog } from '../dist/core/capabilities.js';
import { slimPrices } from '../dist/core/prices.js';
import { slimVision } from '../dist/core/vision.js';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const res = await fetch('https://models.dev/api.json', { signal: AbortSignal.timeout(60_000) });
if (!res.ok) throw new Error(`models.dev answered HTTP ${res.status}`);
const api = await res.json();
const file = slimCatalog(api);
const models = Object.values(file.models).reduce((n, m) => n + Object.keys(m).length, 0);
if (models === 0) throw new Error('no reasoning data in the response');
const target = path.join(root, 'src/core/reasoning-catalog.json');
fs.writeFileSync(target, JSON.stringify(file));
console.log(`${models} models, ${Object.keys(file.models).length} providers, ${fs.statSync(target).size} bytes -> src/core/reasoning-catalog.json`);

const prices = slimPrices(api);
const priced = Object.values(prices.models).reduce((n, m) => n + Object.keys(m).length, 0);
if (priced === 0) throw new Error('no price data in the response');
const priceTarget = path.join(root, 'src/core/price-catalog.json');
fs.writeFileSync(priceTarget, JSON.stringify(prices));
console.log(`${priced} priced models, ${prices.rows.length} distinct prices, ${fs.statSync(priceTarget).size} bytes -> src/core/price-catalog.json`);

const vision = slimVision(api);
if (vision.yes.length === 0) throw new Error('no modality data in the response');
const visionTarget = path.join(root, 'src/core/vision-catalog.json');
fs.writeFileSync(visionTarget, JSON.stringify(vision));
console.log(`${vision.yes.length} models read images, ${vision.no.length} are text only -> src/core/vision-catalog.json`);
