import { execSync } from 'child_process';
import pc from 'picocolors';
import { getCurrentTheme } from './theme.js';
export class GitStatusCache {
    static cachedInfo = undefined;
    static lastCheckTime = 0;
    static TTL_MS = 10000; // 10s fallback TTL
    static getStatus(forceRefresh = false) {
        const now = Date.now();
        if (!forceRefresh && GitStatusCache.cachedInfo !== undefined && (now - GitStatusCache.lastCheckTime < GitStatusCache.TTL_MS)) {
            return GitStatusCache.cachedInfo;
        }
        GitStatusCache.lastCheckTime = now;
        try {
            const branch = execSync('git branch --show-current', {
                stdio: ['ignore', 'pipe', 'ignore'],
                encoding: 'utf8',
                timeout: 1000
            }).trim();
            if (!branch) {
                GitStatusCache.cachedInfo = null;
                return null;
            }
            const status = execSync('git status --porcelain', {
                stdio: ['ignore', 'pipe', 'ignore'],
                encoding: 'utf8',
                timeout: 1000
            }).trim();
            const isDirty = status.length > 0;
            GitStatusCache.cachedInfo = { branch, isDirty };
            return GitStatusCache.cachedInfo;
        }
        catch {
            GitStatusCache.cachedInfo = null;
            return null;
        }
    }
    static invalidate() {
        GitStatusCache.cachedInfo = undefined;
        GitStatusCache.lastCheckTime = 0;
    }
}
export function renderProgressBar(percent, barWidth = 10) {
    const clamped = Math.max(0, Math.min(100, percent));
    const filledCount = Math.round((clamped / 100) * barWidth);
    const emptyCount = Math.max(0, barWidth - filledCount);
    const bar = '█'.repeat(filledCount) + '░'.repeat(emptyCount);
    if (clamped >= 90)
        return pc.red(`[${bar}] ${clamped}%`);
    if (clamped >= 75)
        return pc.yellow(`[${bar}] ${clamped}%`);
    return pc.cyan(`[${bar}] ${clamped}%`);
}
export function formatCostBadge(cost, maxCostUSD) {
    const costFormatted = `$${cost.toFixed(4)}`;
    if (!maxCostUSD || maxCostUSD <= 0) {
        return pc.green(costFormatted);
    }
    const ratio = cost / maxCostUSD;
    if (ratio >= 1.0) {
        return pc.red(costFormatted);
    }
    if (ratio >= 0.8) {
        return pc.yellow(costFormatted);
    }
    return pc.green(costFormatted);
}
export function renderPowerlineStatus(opts) {
    const cols = opts.cols || process.stdout.columns || 80;
    const theme = getCurrentTheme();
    // Mode badge
    let modeBadge = '';
    if (opts.isYolo) {
        modeBadge = pc.bgRed(pc.bold(pc.white(' YOLO ')));
    }
    else if (opts.mode === 'PLAN') {
        modeBadge = pc.bgCyan(pc.bold(pc.black(' PLAN ')));
    }
    else {
        modeBadge = pc.bgGreen(pc.bold(pc.black(' AGENT ')));
    }
    // Model shortening for narrow screens
    let displayModel = opts.model;
    if (cols < 75 && displayModel.length > 18) {
        const parts = displayModel.split('/');
        displayModel = parts.length > 1 ? parts.slice(1).join('/') : displayModel;
        if (displayModel.length > 18) {
            displayModel = displayModel.slice(0, 15) + '…';
        }
    }
    // Git info
    const git = GitStatusCache.getStatus();
    const gitStr = git ? ` · ${pc.magenta(git.branch)}${git.isDirty ? pc.yellow('✱') : ''}` : '';
    // Context progress
    const usagePercent = Math.min(100, Math.round((opts.currentTokens / Math.max(1, opts.maxTokens)) * 100));
    const barWidth = cols < 60 ? 6 : 10;
    const progressStr = renderProgressBar(usagePercent, barWidth);
    const costStr = formatCostBadge(opts.cost, opts.maxCostUSD);
    if (cols < 65) {
        // 2-line layout for mobile Termux
        const line1 = `${modeBadge} ${pc.bold(displayModel)}${gitStr}`;
        const line2 = `${pc.dim('│')}  Context ${progressStr} · ${costStr}`;
        return `${line1}\n${line2}`;
    }
    // 1-line powerline layout for desktop
    return `${modeBadge} ${pc.bold(displayModel)}${gitStr} · Context ${progressStr} · ${costStr}`;
}
