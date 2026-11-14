// Puts the release APK of the Android app (the sibling folder ../devx-android) into a GitHub release of this repository,
// so that the phone app is downloaded from the same Releases page as devx itself.
//
//   node scripts/attach-android.mjs v2.0.0            build the app, then upload it to the release v2.0.0 (asks first)
//   node scripts/attach-android.mjs v2.0.0 --no-build use the APK that is already built
//   node scripts/attach-android.mjs v2.0.0 --yes      do not ask
//
// It needs the GitHub CLI (`gh`), logged in, and the release must exist already. It never creates a release or a tag.
import { execFileSync, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline/promises';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const android = process.env.DEVX_ANDROID_DIR || path.resolve(root, '..', 'devx-android');
const args = process.argv.slice(2);
const tag = args.find((a) => !a.startsWith('--'));
const flags = new Set(args.filter((a) => a.startsWith('--')));
const fail = (message) => { console.error(`\n  ${message}\n`); process.exit(1); };

if (!tag) fail('Say which release: node scripts/attach-android.mjs v2.0.0');
if (!fs.existsSync(path.join(android, 'gradlew'))) fail(`The Android app is not at ${android} (set DEVX_ANDROID_DIR to its folder).`);

const gradle = fs.readFileSync(path.join(android, 'app', 'build.gradle.kts'), 'utf8');
const version = /versionName\s*=\s*"([^"]+)"/.exec(gradle)?.[1];
if (!version) fail('Could not read versionName of the Android app.');

try { execFileSync('gh', ['release', 'view', tag, '--json', 'tagName'], { cwd: root, stdio: 'ignore' }); }
catch { fail(`There is no release ${tag} in this repository (or gh is not logged in). Make the release first.`); }

if (!flags.has('--no-build')) {
  console.log(`\n  Building the Android app ${version} ...`);
  const built = spawnSync('./gradlew', ['assembleRelease'], { cwd: android, stdio: 'inherit' });
  if (built.status !== 0) fail('The Android build failed.');
}

const apk = path.join(android, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
if (!fs.existsSync(apk)) fail(`No APK at ${apk}. Build it first (or drop --no-build).`);

const out = fs.mkdtempSync(path.join(os.tmpdir(), 'devx-android-'));
const name = `devx-android-${version}.apk`;
const file = path.join(out, name);
fs.copyFileSync(apk, file);
const sum = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
fs.writeFileSync(`${file}.sha256`, `${sum}  ${name}\n`);
const mb = (fs.statSync(file).size / 1048576).toFixed(1);

// Which key signed it: users can update the app only from builds signed with the same one.
let signer = 'unknown (apksigner not found)';
try { signer = /SHA-256 digest: ([0-9a-f]+)/i.exec(execFileSync('apksigner', ['verify', '--print-certs', file], { encoding: 'utf8' }))?.[1] ?? signer; } catch { /* keep the note */ }
const keyFile = path.join(os.homedir(), '.config', 'devx-android', 'signing.properties');
if (!fs.existsSync(keyFile) && !process.env.DEVX_SIGNING_PROPERTIES) console.log(`\n  WARNING: no release key at ${keyFile}: this APK is signed with the debug key, which is only fit for trying it out.`);

console.log(`\n  ${name}  (${mb} MB)\n  sha256 ${sum}\n  signed with the key ${signer}\n  goes to the release ${tag} of this repository.\n`);
if (!flags.has('--yes')) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const answer = (await rl.question('  Upload it now? [y/N] ')).trim().toLowerCase();
  rl.close();
  if (answer !== 'y' && answer !== 'yes') { console.log(`\n  Not uploaded. The files are in ${out}\n`); process.exit(0); }
}

execFileSync('gh', ['release', 'upload', tag, file, `${file}.sha256`, '--clobber'], { cwd: root, stdio: 'inherit' });
console.log(`\n  Done: the release ${tag} now has ${name}.\n`);
