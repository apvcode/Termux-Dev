import pc from 'picocolors';
import { getCurrentTheme } from '../cli/theme.js';
function stripAnsi(str) {
    return str.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '');
}
export class UsageTracker {
    static instance;
    bytesSent = 0;
    bytesReceived = 0;
    requestsCount = 0;
    promptTokens = 0;
    completionTokens = 0;
    totalCost = 0;
    dataSaverLimitMB;
    warnedThreshold = false;
    constructor() { }
    static getInstance() {
        if (!UsageTracker.instance) {
            UsageTracker.instance = new UsageTracker();
        }
        return UsageTracker.instance;
    }
    setLimit(limitMB) {
        this.dataSaverLimitMB = limitMB;
    }
    recordRequest(payloadBytes) {
        this.bytesSent += payloadBytes;
        this.requestsCount += 1;
    }
    recordResponseChunk(chunkBytes) {
        this.bytesReceived += chunkBytes;
    }
    recordTokens(promptTokens, completionTokens, cost) {
        this.promptTokens += promptTokens;
        this.completionTokens += completionTokens;
        this.totalCost += cost;
    }
    getSummary() {
        return {
            bytesSent: this.bytesSent,
            bytesReceived: this.bytesReceived,
            totalBytes: this.bytesSent + this.bytesReceived,
            requestsCount: this.requestsCount,
            promptTokens: this.promptTokens,
            completionTokens: this.completionTokens,
            totalTokens: this.promptTokens + this.completionTokens,
            totalCost: this.totalCost,
            dataSaverLimitMB: this.dataSaverLimitMB
        };
    }
    static formatBytes(bytes) {
        if (bytes < 1024)
            return `${bytes} B`;
        if (bytes < 1024 * 1024)
            return `${(bytes / 1024).toFixed(1)} KB`;
        return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
    }
    static formatTokens(n) {
        if (n >= 1000000)
            return `${(n / 1000000).toFixed(2)}M`;
        if (n >= 1000)
            return `${(n / 1000).toFixed(1)}k`;
        return `${n}`;
    }
    renderUsageCard() {
        const theme = getCurrentTheme();
        const summary = this.getSummary();
        const cols = Math.min(process.stdout.columns || 80, 75);
        const boxWidth = Math.max(38, cols - 4);
        const innerWidth = boxWidth - 6;
        const padRow = (label, value) => {
            const visibleValLen = stripAnsi(value).length;
            const plainLen = label.length + visibleValLen;
            const spaces = Math.max(1, innerWidth - plainLen);
            return `│  ${pc.bold(label)}${' '.repeat(spaces)}${value}  │`;
        };
        const header = theme.colorFn('┌─ ') + pc.bold('📊 Session Bandwidth & Usage Monitor') + ' ' + theme.colorFn('─'.repeat(Math.max(2, boxWidth - 41)) + '┐');
        const divider = theme.colorFn('├' + '─'.repeat(boxWidth - 2) + '┤');
        const footer = theme.colorFn('└' + '─'.repeat(boxWidth - 2) + '┘');
        const totalBandwidthStr = pc.cyan(UsageTracker.formatBytes(summary.totalBytes));
        const sentStr = pc.dim(UsageTracker.formatBytes(summary.bytesSent));
        const recvStr = pc.dim(UsageTracker.formatBytes(summary.bytesReceived));
        const reqStr = pc.yellow(`${summary.requestsCount}`);
        const tokenStr = pc.magenta(`${UsageTracker.formatTokens(summary.totalTokens)} tokens`);
        const promptTokenStr = pc.dim(`${UsageTracker.formatTokens(summary.promptTokens)} in`);
        const compTokenStr = pc.dim(`${UsageTracker.formatTokens(summary.completionTokens)} out`);
        const costStr = pc.green(`$${summary.totalCost.toFixed(4)}`);
        const lines = [
            header,
            padRow('Total Network Traffic:', totalBandwidthStr),
            padRow('  • Upload (Sent):', sentStr),
            padRow('  • Download (Recv):', recvStr),
            padRow('API Requests Count:', reqStr),
            divider,
            padRow('Tokens Consumed:', tokenStr),
            padRow('  • Breakdown:', `${promptTokenStr} / ${compTokenStr}`),
            padRow('Session Cost:', costStr)
        ];
        if (summary.dataSaverLimitMB) {
            const usedMB = summary.totalBytes / (1024 * 1024);
            const pct = Math.min(100, Math.round((usedMB / summary.dataSaverLimitMB) * 100));
            const limitStr = pct >= 90 ? pc.red(`${usedMB.toFixed(1)} / ${summary.dataSaverLimitMB} MB (${pct}%)`) : pc.cyan(`${usedMB.toFixed(1)} / ${summary.dataSaverLimitMB} MB (${pct}%)`);
            lines.push(divider);
            lines.push(padRow('Data Saver Limit:', limitStr));
        }
        lines.push(footer);
        return lines.join('\n') + '\n';
    }
    checkThresholdWarning() {
        if (!this.dataSaverLimitMB || this.warnedThreshold)
            return null;
        const usedMB = (this.bytesSent + this.bytesReceived) / (1024 * 1024);
        if (usedMB >= this.dataSaverLimitMB) {
            this.warnedThreshold = true;
            return `⚠️  [DATA SAVER WARNING] Session network traffic has reached ${usedMB.toFixed(1)} MB (Limit: ${this.dataSaverLimitMB} MB)!`;
        }
        return null;
    }
}
