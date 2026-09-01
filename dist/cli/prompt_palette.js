import pc from 'picocolors';
export function filterCommandPalette(query, commands) {
    if (!query || query === '/') {
        return [...commands];
    }
    const q = query.toLowerCase().replace(/^\//, '');
    return commands.filter((c) => {
        const name = c.cmd.toLowerCase().replace(/^\//, '');
        return name.includes(q) || c.desc.toLowerCase().includes(q);
    });
}
export function estimatePromptTokens(text) {
    if (!text)
        return 0;
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
export function formatPromptTokenBadge(tokens, threshold = 30) {
    if (tokens < threshold)
        return '';
    if (tokens >= 1000) {
        return pc.dim(`(~${(tokens / 1000).toFixed(1)}k tok)`);
    }
    return pc.dim(`(~${tokens} tok)`);
}
