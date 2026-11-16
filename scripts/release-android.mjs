// Puts the Android app (the sibling folder ../devx-android) on its own page of this repository: the release "android-app".
//
// The app and devx have separate versions, so the app does not ride on devx's releases. It has ONE page that never changes its address,
// https://github.com/apvcode/Termux-Dev/releases/tag/android-app, and every version of the app is a file on it:
// devx-android-2.3.0.apk (+ a .sha256), devx-android-2.4.0.apk, ... A new version is added next to the old ones; a file that is already
// there is never replaced. The page is not marked "latest", so the "Latest release" of the repository stays devx's.
//
//   node scripts/release-android.mjs              build the app and add devx-android-<versionName>.apk to the page (asks first)
//   node scripts/release-android.mjs --dry-run    show what would be done, change nothing
//   node scripts/release-android.mjs --no-build   use the APK that is already built
//   node scripts/release-android.mjs --yes        do not ask
//   node scripts/release-android.mjs --notes "what is new"   one line for the list of versions on the page
//   node scripts/release-android.mjs --target android        the branch the page's tag is made on, the first time (default: android)
//
// It needs the GitHub CLI (`gh`), logged in.
import { execFileSync, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline/promises';
import { fileURLToPath } from 'node:url';

const TAG = 'android-app';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const android = process.env.DEVX_ANDROID_DIR || path.resolve(root, '..', 'devx-android');
const args = process.argv.slice(2);
const valued = ['--notes', '--target'];
const flags = new Set(args.filter((a) => a.startsWith('--') && !valued.includes(a)));
const value = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
const fail = (message) => { console.error(`\n  ${message}\n`); process.exit(1); };
const gh = (list, options = {}) => execFileSync('gh', list, { cwd: root, encoding: 'utf8', ...options });

if (!fs.existsSync(path.join(android, 'gradlew'))) fail(`The Android app is not at ${android} (set DEVX_ANDROID_DIR to its folder).`);

const gradle = fs.readFileSync(path.join(android, 'app', 'build.gradle.kts'), 'utf8');
const version = /versionName\s*=\s*"([^"]+)"/.exec(gradle)?.[1];
if (!version) fail('Could not read versionName of the Android app.');
const dry = flags.has('--dry-run');
const name = `devx-android-${version}.apk`;

// The page, and the files it has now.
let page = null;
try { page = JSON.parse(gh(['release', 'view', TAG, '--json', 'tagName,body,assets'], { stdio: ['ignore', 'pipe', 'ignore'] })); } catch { /* no page yet */ }
if (page?.assets?.some((a) => a.name === name)) fail(`${name} is already on the page ${TAG}. A published file is not replaced: raise versionName in the app (and versionCode) for a new version.`);

if (!flags.has('--no-build')) {
  console.log(`\n  Building the Android app ${version} ...`);
  const built = spawnSync('./gradlew', ['assembleRelease'], { cwd: android, stdio: 'inherit' });
  if (built.status !== 0) fail('The Android build failed.');
}

const apk = path.join(android, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
if (!fs.existsSync(apk)) fail(`No APK at ${apk}. Build it first (or drop --no-build).`);

const out = fs.mkdtempSync(path.join(os.tmpdir(), 'devx-android-'));
const file = path.join(out, name);
fs.copyFileSync(apk, file);
const sum = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
fs.writeFileSync(`${file}.sha256`, `${sum}  ${name}\n`);
const mb = (fs.statSync(file).size / 1048576).toFixed(1);

// Which key signed it: the app updates over an installed one only when the key is the same.
let signer = 'unknown (apksigner not found)';
try { signer = /SHA-256 digest: ([0-9a-f]+)/i.exec(execFileSync('apksigner', ['verify', '--print-certs', file], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }))?.[1] ?? signer; } catch { /* keep the note */ }
const keyFile = path.join(os.homedir(), '.config', 'devx-android', 'signing.properties');
if (!fs.existsSync(keyFile) && !process.env.DEVX_SIGNING_PROPERTIES) fail(`No release key at ${keyFile}: this APK is signed with the debug key and cannot update an installed release. Set the key up first.`);

// The text of the page: how to install, then one entry per version, the newest first.
const today = new Date().toISOString().slice(0, 10);
const entry = [`### ${version} · ${today}`, '', value('--notes') ? `${value('--notes')}\n` : '', `- \`${name}\` · ${mb} MB · SHA-256 \`${sum}\``, `- signing key (SHA-256) \`${signer}\``].filter((l, i) => !(l === '' && i === 2 && !value('--notes'))).join('\n');
const intro = [
  '# devx for Android',
  '',
  'Open the newest `devx-android-X.Y.Z.apk` below on the phone and allow installing from this source. Android 8.0 (API 26) or newer. A new version installs over the old one (every file is signed with the same key). The app has its own versions; they are not devx\'s.',
  '',
  'Every version is a file here and stays: nothing on this page is ever replaced.',
  '',
  '## Versions',
  '',
].join('\n');
const old = page?.body?.includes('## Versions') ? page.body.split('## Versions')[1].trim() : '';
const body = `${intro}\n${entry}\n${old ? `\n${old}\n` : ''}`;

console.log(`\n  ${name}  (${mb} MB)\n  sha256 ${sum}\n  signed with the key ${signer}\n  goes to the page ${TAG} (https://github.com/apvcode/Termux-Dev/releases/tag/${TAG}), ${page ? 'which exists; the file is added next to the others' : 'which is made now'}; not marked latest.\n`);
if (dry) { console.log(`  Dry run: nothing was done. The files are in ${out}\n`); process.exit(0); }
if (!flags.has('--yes')) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const answer = (await rl.question('  Add it now? [y/N] ')).trim().toLowerCase();
  rl.close();
  if (answer !== 'y' && answer !== 'yes') { console.log(`\n  Not added. The files are in ${out}\n`); process.exit(0); }
}

const notesFile = path.join(out, 'notes.md');
fs.writeFileSync(notesFile, body);
if (!page) gh(['release', 'create', TAG, file, `${file}.sha256`, '--title', 'devx for Android', '--notes-file', notesFile, '--latest=false', '--target', value('--target') || 'android'], { stdio: 'inherit' });
else {
  // No --clobber: a file that is there stays as it is.
  gh(['release', 'upload', TAG, file, `${file}.sha256`], { stdio: 'inherit' });
  gh(['release', 'edit', TAG, '--notes-file', notesFile, '--latest=false'], { stdio: 'inherit' });
}
console.log(`\n  Done: https://github.com/apvcode/Termux-Dev/releases/tag/${TAG}\n`);
