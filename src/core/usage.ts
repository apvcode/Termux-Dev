import pc from 'picocolors';
import { getCurrentTheme } from '../cli/theme.js';
import { CostLedger, CostState, formatMoney } from './cost.js';
import type { TokenUsage } from './types.js';

export interface UsageSummary {
  requestsCount: number;
  bytesSent: number;
  bytesReceived: number;
  totalBytes: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  totalCost: number;
  /** How to read totalCost: estimated, and how many requests could not be priced. */
  costState: CostState;
  cacheReadTokens: number;
  /** Input tokens of the last request as the provider counted them (0 before the first request). */
  lastPromptTokens: number;
  dataSaverLimitMB?: number;
}

function stripAnsi(str: string): string {
  return str.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '');
}

export class UsageTracker {
  private static instance: UsageTracker;

  private bytesSent = 0;
  private bytesReceived = 0;
  private requestsCount = 0;
  private promptTokens = 0;
  private completionTokens = 0;
  private ledger = new CostLedger();
  private cacheReadTokens = 0;
  private cacheWriteTokens = 0;
  private lastPromptTokens = 0;
  private dataSaverLimitMB?: number;
  private warnedThreshold: boolean = false;

  private constructor() {}

  static getInstance(): UsageTracker {
    if (!UsageTracker.instance) {
      UsageTracker.instance = new UsageTracker();
    }
    return UsageTracker.instance;
  }

  setLimit(limitMB?: number) {
    this.dataSaverLimitMB = limitMB;
  }

  /** Starts counting from zero (new session). The data saver limit itself is kept. */
  reset() {
    this.bytesSent = 0;
    this.bytesReceived = 0;
    this.requestsCount = 0;
    this.promptTokens = 0;
    this.completionTokens = 0;
    this.ledger.reset();
    this.cacheReadTokens = 0;
    this.cacheWriteTokens = 0;
    this.lastPromptTokens = 0;
    this.warnedThreshold = false;
  }

  recordRequest(payloadBytes: number) {
    this.bytesSent += payloadBytes;
    this.requestsCount += 1;
  }

  recordResponseChunk(chunkBytes: number) {
    this.bytesReceived += chunkBytes;
  }

  /** Records the tokens of one request; an undefined cost means the price is not known. */
  recordTokens(promptTokens: number, completionTokens: number, cost?: number, costSource?: TokenUsage['costSource']) {
    this.promptTokens += promptTokens;
    this.completionTokens += completionTokens;
    this.ledger.add({ cost, costSource });
  }

  recordUsage(usage: TokenUsage) {
    this.recordTokens(usage.promptTokens, usage.completionTokens, usage.cost, usage.costSource);
    this.cacheReadTokens += usage.cacheReadTokens || 0;
    this.cacheWriteTokens += usage.cacheWriteTokens || 0;
    if (!usage.tokensEstimated) this.lastPromptTokens = usage.promptTokens;
  }

  getSummary(): UsageSummary {
    return {
      bytesSent: this.bytesSent,
      bytesReceived: this.bytesReceived,
      totalBytes: this.bytesSent + this.bytesReceived,
      requestsCount: this.requestsCount,
      promptTokens: this.promptTokens,
      completionTokens: this.completionTokens,
      totalTokens: this.promptTokens + this.completionTokens,
      totalCost: this.ledger.total,
      costState: this.ledger.state(),
      cacheReadTokens: this.cacheReadTokens,
      lastPromptTokens: this.lastPromptTokens,
      dataSaverLimitMB: this.dataSaverLimitMB
    };
  }

  static formatBytes(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  }

  static formatTokens(n: number): string {
    if (n >= 1000000) return `${(n / 1000000).toFixed(2)}M`;
    if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
    return `${n}`;
  }

  renderUsageCard(): string {
    const theme = getCurrentTheme();
    const summary = this.getSummary();
    const cols = Math.min(process.stdout.columns || 80, 75);
    const boxWidth = Math.max(38, cols - 4);
    const innerWidth = boxWidth - 6;

    const padRow = (label: string, value: string) => {
      const visibleValLen = stripAnsi(value).length;
      const plainLen = label.length + visibleValLen;
      const spaces = Math.max(1, innerWidth - plainLen);
      return `│  ${pc.bold(label)}${' '.repeat(spaces)}${value}  │`;
    };

    const header = theme.colorFn('┌─ ') + pc.bold('Session Bandwidth & Usage Monitor') + ' ' + theme.colorFn('─'.repeat(Math.max(2, boxWidth - 41)) + '┐');
    const divider = theme.colorFn('├' + '─'.repeat(boxWidth - 2) + '┤');
    const footer = theme.colorFn('└' + '─'.repeat(boxWidth - 2) + '┘');

    const totalBandwidthStr = pc.cyan(UsageTracker.formatBytes(summary.totalBytes));
    const sentStr = pc.dim(UsageTracker.formatBytes(summary.bytesSent));
    const recvStr = pc.dim(UsageTracker.formatBytes(summary.bytesReceived));
    const reqStr = pc.yellow(`${summary.requestsCount}`);
    const tokenStr = pc.magenta(`${UsageTracker.formatTokens(summary.totalTokens)} tokens`);
    const promptTokenStr = pc.dim(`${UsageTracker.formatTokens(summary.promptTokens)} in`);
    const compTokenStr = pc.dim(`${UsageTracker.formatTokens(summary.completionTokens)} out`);
    const costStr = pc.green(formatMoney(summary.totalCost, summary.costState));

    const lines: string[] = [
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
    if (summary.cacheReadTokens > 0) {
      lines.push(padRow('  • Cached input:', pc.dim(`${UsageTracker.formatTokens(summary.cacheReadTokens)} read`)));
    }
    if (summary.costState.estimated) lines.push(padRow('  • Prices:', pc.dim('catalogue (estimate)')));
    if ((summary.costState.unpriced ?? 0) > 0) {
      lines.push(padRow('  • No price for:', pc.dim(`${summary.costState.unpriced} request${summary.costState.unpriced === 1 ? '' : 's'}`)));
    }

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

  checkThresholdWarning(): string | null {
    if (!this.dataSaverLimitMB || this.warnedThreshold) return null;
    const usedMB = (this.bytesSent + this.bytesReceived) / (1024 * 1024);
    if (usedMB >= this.dataSaverLimitMB) {
      this.warnedThreshold = true;
      return `[DATA SAVER WARNING] Session network traffic has reached ${usedMB.toFixed(1)} MB (Limit: ${this.dataSaverLimitMB} MB)!`;
    }
    return null;
  }
}
