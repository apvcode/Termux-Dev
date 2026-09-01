import pc from 'picocolors';
export class LiveTelemetryTracker {
    startTime = 0;
    phase = 'connecting';
    totalTokens = 0;
    samples = [];
    windowMs = 2000;
    constructor(windowMs = 2000) {
        this.windowMs = windowMs;
    }
    start(phase = 'connecting') {
        this.startTime = Date.now();
        this.phase = phase;
        this.totalTokens = 0;
        this.samples = [];
    }
    setPhase(phase) {
        this.phase = phase;
    }
    recordTokens(count) {
        if (count <= 0)
            return;
        const now = Date.now();
        this.totalTokens += count;
        this.samples.push({ timestamp: now, tokens: count });
        this.pruneOldSamples(now);
    }
    estimateTokensFromText(text) {
        if (!text)
            return 0;
        // Fast token estimation: ~0.8 tokens per Cyrillic char, ~0.25 per ASCII
        let tokens = 0;
        for (let i = 0; i < text.length; i++) {
            const code = text.charCodeAt(i);
            if (code >= 0x0400 && code <= 0x04ff) {
                tokens += 0.8;
            }
            else if (code > 0x07ff) {
                tokens += 1.2;
            }
            else {
                tokens += 0.25;
            }
        }
        return Math.max(1, Math.round(tokens));
    }
    pruneOldSamples(now) {
        const threshold = now - this.windowMs;
        while (this.samples.length > 0 && this.samples[0].timestamp < threshold) {
            this.samples.shift();
        }
    }
    getTokensPerSecond() {
        const now = Date.now();
        this.pruneOldSamples(now);
        if (this.samples.length === 0)
            return 0;
        const windowTokens = this.samples.reduce((acc, s) => acc + s.tokens, 0);
        const earliest = this.samples[0].timestamp;
        const durationSec = Math.max(0.2, (now - earliest) / 1000);
        return Math.round(windowTokens / durationSec);
    }
    getElapsedSeconds() {
        if (this.startTime === 0)
            return 0;
        return Math.max(0, Math.round((Date.now() - this.startTime) / 1000));
    }
    getTotalTokens() {
        return this.totalTokens;
    }
    static formatTokenCount(n) {
        if (n >= 1_000_000)
            return `${(n / 1_000_000).toFixed(1)}M`;
        if (n >= 1_000)
            return `${(n / 1_000).toFixed(1)}k`;
        return `${Math.round(n)}`;
    }
    formatLabel() {
        const secs = `${this.getElapsedSeconds()}s`;
        const tokens = LiveTelemetryTracker.formatTokenCount(this.totalTokens);
        const tps = this.getTokensPerSecond();
        if (this.phase === 'connecting') {
            return `connecting… ${pc.dim(secs)}`;
        }
        if (this.phase === 'thinking') {
            if (this.totalTokens > 0) {
                return `thinking… ${pc.dim(`${secs} · ${tokens} tok · ${tps} tok/s`)}`;
            }
            return `thinking… ${pc.dim(secs)}`;
        }
        if (this.phase === 'generating') {
            if (this.totalTokens > 0) {
                return `generating… ${pc.dim(`${secs} · ${tokens} tok · ${tps} tok/s`)}`;
            }
            return `generating… ${pc.dim(secs)}`;
        }
        return `executing… ${pc.dim(secs)}`;
    }
    reset() {
        this.startTime = 0;
        this.phase = 'connecting';
        this.totalTokens = 0;
        this.samples = [];
    }
}
