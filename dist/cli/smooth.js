/**
 * High-performance fluid token streamer for terminal vibe-coding.
 * Turns jerky SSE network chunk bursts into buttery-smooth word-by-word and character-by-character flow.
 */
export class SmoothStreamer {
    queue = [];
    timer = null;
    onWrite;
    constructor(onWrite) {
        this.onWrite = onWrite;
    }
    push(text) {
        if (!text)
            return;
        this.queue.push(text);
        if (!this.timer) {
            this.startLoop();
        }
    }
    startLoop() {
        this.timer = setInterval(() => {
            this.tick();
        }, 10);
    }
    tick() {
        if (this.queue.length === 0) {
            if (this.timer) {
                clearInterval(this.timer);
                this.timer = null;
            }
            return;
        }
        const fullStr = this.queue.join('');
        this.queue = [];
        // Adaptive chunk size based on remaining buffer backlog:
        // Small backlog (< 30 chars) -> 3-6 chars / tick (~60-100 fps typing effect)
        // Medium backlog (30-100 chars) -> 12-20 chars / tick
        // Large backlog (> 100 chars) -> flush 50-100 chars / tick (never lags behind fast LLMs)
        let take = 4;
        if (fullStr.length > 200) {
            take = fullStr.length;
        }
        else if (fullStr.length > 100) {
            take = 25;
        }
        else if (fullStr.length > 40) {
            take = 10;
        }
        else {
            take = 4;
        }
        if (take < fullStr.length) {
            const code = fullStr.charCodeAt(take - 1);
            // If take ends on a high surrogate (0xD800..0xDBFF), advance by 1 to include low surrogate
            if (code >= 0xD800 && code <= 0xDBFF) {
                take++;
            }
        }
        const chunk = fullStr.slice(0, take);
        const rest = fullStr.slice(take);
        if (rest) {
            this.queue.unshift(rest);
        }
        this.onWrite(chunk);
    }
    flush() {
        if (this.timer) {
            clearInterval(this.timer);
            this.timer = null;
        }
        if (this.queue.length > 0) {
            const remaining = this.queue.join('');
            this.queue = [];
            this.onWrite(remaining);
        }
    }
    reset() {
        if (this.timer) {
            clearInterval(this.timer);
            this.timer = null;
        }
        this.queue = [];
    }
}
