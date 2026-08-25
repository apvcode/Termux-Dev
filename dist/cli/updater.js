import { execSync, spawn } from 'child_process';
import path from 'path';
import fs from 'fs/promises';
import pc from 'picocolors';
import { select } from '@inquirer/prompts';
import { fileURLToPath } from 'url';
const GITHUB_REPO_URL = 'https://github.com/apvcode/Termux-Dev';
const REMOTE_PKG_URL = 'https://raw.githubusercontent.com/apvcode/Termux-Dev/main/package.json';
const REMOTE_RELEASE_URL = 'https://api.github.com/repos/apvcode/Termux-Dev/releases/latest';
// Compare semantic versions (e.g. 1.1.0 > 1.0.0)
export function isNewerVersion(remote, current) {
    const clean = (v) => v.replace(/^v/, '').trim();
    const rParts = clean(remote).split('.').map(n => parseInt(n, 10) || 0);
    const cParts = clean(current).split('.').map(n => parseInt(n, 10) || 0);
    for (let i = 0; i < Math.max(rParts.length, cParts.length); i++) {
        const r = rParts[i] || 0;
        const c = cParts[i] || 0;
        if (r > c)
            return true;
        if (r < c)
            return false;
    }
    return false;
}
export async function getCurrentVersion() {
    try {
        const __filename = fileURLToPath(import.meta.url);
        const __dirname = path.dirname(__filename);
        const pkgPath = path.resolve(__dirname, '../../package.json');
        const data = await fs.readFile(pkgPath, 'utf8');
        const parsed = JSON.parse(data);
        return parsed.version || '1.0.0';
    }
    catch {
        return '1.0.0';
    }
}
export async function checkForUpdates(timeoutMs = 10000) {
    const currentVersion = await getCurrentVersion();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
        // 1. Try fetching latest package.json with cache-busting timestamp
        const cacheBusterUrl = `${REMOTE_PKG_URL}?t=${Date.now()}`;
        const res = await fetch(cacheBusterUrl, {
            signal: controller.signal,
            headers: {
                'User-Agent': 'devx-updater',
                'Cache-Control': 'no-cache, no-store, must-revalidate',
                'Pragma': 'no-cache'
            }
        });
        clearTimeout(timer);
        let latestVersion = '';
        let releaseNotes = '';
        if (res.ok) {
            const pkg = await res.json();
            latestVersion = (pkg.version || '').replace(/^v/, '').trim();
        }
        // 2. Also check latest GitHub release tag
        try {
            const relRes = await fetch(`${REMOTE_RELEASE_URL}?t=${Date.now()}`, {
                headers: { 'User-Agent': 'devx-updater' }
            });
            if (relRes.ok) {
                const relData = await relRes.json();
                const relTag = (relData.tag_name || relData.name || '').replace(/^v/, '').trim();
                if (relTag && (!latestVersion || isNewerVersion(relTag, latestVersion))) {
                    latestVersion = relTag;
                }
                releaseNotes = relData.body || '';
            }
        }
        catch { }
        if (latestVersion && isNewerVersion(latestVersion, currentVersion)) {
            return {
                updateAvailable: true,
                currentVersion,
                latestVersion,
                releaseNotes
            };
        }
        return {
            updateAvailable: false,
            currentVersion,
            latestVersion: latestVersion || currentVersion
        };
        return {
            updateAvailable: false,
            currentVersion,
            latestVersion: currentVersion,
            error: `HTTP ${res.status}`
        };
    }
    catch (err) {
        clearTimeout(timer);
        return {
            updateAvailable: false,
            currentVersion,
            latestVersion: currentVersion,
            error: err.name === 'AbortError' ? 'Timeout (10s)' : err.message
        };
    }
}
export async function performSelfUpdate(latestVersion) {
    const __filename = fileURLToPath(import.meta.url);
    const projectRoot = path.resolve(path.dirname(__filename), '../../');
    console.log('\n' + pc.bold(pc.cyan('─── 🚀 Starting devx Update ──────────────────────────')));
    const steps = [
        {
            title: '📦 Pulling latest updates from GitHub repository...',
            action: () => {
                try {
                    execSync('git pull origin main --quiet', { cwd: projectRoot, stdio: 'pipe' });
                }
                catch {
                    // If not git clone or detached, try global git install or fetch
                    try {
                        execSync('git pull --quiet', { cwd: projectRoot, stdio: 'pipe' });
                    }
                    catch {
                        execSync(`npm install -g git+${GITHUB_REPO_URL}.git --quiet`, { stdio: 'pipe' });
                    }
                }
            }
        },
        {
            title: '🔨 Building and compiling TypeScript sources...',
            action: () => {
                execSync('npm install --quiet', { cwd: projectRoot, stdio: 'pipe' });
                execSync('npm run build --quiet', { cwd: projectRoot, stdio: 'pipe' });
            }
        },
        {
            title: '🔑 Ensuring binary execution permissions & linking...',
            action: () => {
                try {
                    execSync('chmod +x bin/* 2>/dev/null || true', { cwd: projectRoot, stdio: 'pipe' });
                }
                catch { }
                try {
                    execSync('npm link --quiet', { cwd: projectRoot, stdio: 'pipe' });
                }
                catch { }
            }
        }
    ];
    for (let i = 0; i < steps.length; i++) {
        const step = steps[i];
        process.stdout.write(`  ${pc.cyan('⚡')} [${i + 1}/${steps.length}] ${pc.white(step.title)}\n`);
        // Animation spinner for each step
        const frames = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];
        let fIdx = 0;
        const interval = setInterval(() => {
            process.stdout.write(`\r  ${pc.cyan(frames[fIdx++ % frames.length])} ${pc.dim(step.title)} `);
        }, 80);
        try {
            await new Promise(resolve => setTimeout(resolve, 300));
            step.action();
            clearInterval(interval);
            process.stdout.write(`\r  ${pc.green('✔')} ${pc.bold(pc.white(step.title))}\n`);
        }
        catch (err) {
            clearInterval(interval);
            process.stdout.write(`\r  ${pc.red('✖')} ${pc.red(step.title)} - ${err.message}\n`);
            console.log(pc.red(`\nUpdate failed during step ${i + 1}: ${err.message}`));
            return false;
        }
    }
    console.log(pc.bold(pc.green(`\n✅ Successfully updated to v${latestVersion}! Restarting devx...\n`)));
    console.log(pc.bold(pc.cyan('──────────────────────────────────────────────────────\n')));
    await new Promise(r => setTimeout(r, 1000));
    // Spawn new devx process and exit current
    try {
        const child = spawn(process.argv[0], process.argv.slice(1), {
            stdio: 'inherit',
            detached: true
        });
        child.unref();
        process.exit(0);
    }
    catch {
        process.exit(0);
    }
    return true;
}
export async function runStartupUpdateCheck(config) {
    // If updates checking is disabled in settings, return immediately
    if (config.checkUpdates === false) {
        return;
    }
    const currentVersion = await getCurrentVersion();
    const frames = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];
    let fIdx = 0;
    let isDone = false;
    const spinner = setInterval(() => {
        if (isDone)
            return;
        const frame = pc.cyan(frames[fIdx++ % frames.length]);
        process.stdout.write(`\r  ${frame} ${pc.dim(`Checking for updates from`)} ${pc.cyan('apvcode/Termux-Dev')} ${pc.dim(`(v${currentVersion})...`)} `);
    }, 75);
    const result = await checkForUpdates(10000);
    isDone = true;
    clearInterval(spinner);
    // Clear spinner line
    process.stdout.write('\r\x1b[K');
    if (result.updateAvailable) {
        console.log();
        console.log(pc.bold(pc.cyan('  ┌───────────────────────────────────────────────────────────┐')));
        console.log(pc.bold(pc.cyan('  │')) + pc.bold(pc.yellow(`  🚀 A new update is available: `)) + pc.dim(`v${result.currentVersion}`) + pc.bold(pc.green(` ➔ v${result.latestVersion}`)) + ' '.repeat(Math.max(1, 23 - result.currentVersion.length - result.latestVersion.length)) + pc.bold(pc.cyan('│')));
        console.log(pc.bold(pc.cyan('  │')) + pc.dim(`  Repository: https://github.com/apvcode/Termux-Dev          `) + pc.bold(pc.cyan('│')));
        console.log(pc.bold(pc.cyan('  └───────────────────────────────────────────────────────────┘')));
        console.log();
        try {
            const choice = await select({
                message: pc.bold('Do you want to update devx now?'),
                choices: [
                    {
                        name: `⚡ Update now (Pull v${result.latestVersion}, rebuild & restart)`,
                        value: 'update',
                        description: 'Download latest code, compile TypeScript, and restart devx'
                    },
                    {
                        name: '⏭  Skip for now (Continue to chat)',
                        value: 'skip',
                        description: 'Keep current version and start session'
                    }
                ]
            });
            if (choice === 'update') {
                await performSelfUpdate(result.latestVersion);
            }
        }
        catch {
            // If user presses Ctrl+C / cancels prompt, continue
        }
    }
    else {
        // Briefly show up to date message
        process.stdout.write(`  ${pc.green('✔')} ${pc.dim(`devx is up to date (v${result.currentVersion})`)}\n`);
        await new Promise(r => setTimeout(r, 450));
        // Clean up line
        process.stdout.write('\x1b[1A\x1b[2K');
    }
}
