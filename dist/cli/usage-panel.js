import { LiveBlock } from './liveblock.js';
import { splitKeys } from './keys.js';
import { palette, formatTokens } from './ui.js';
import { displayWidth, padEndWidth, truncateWidth } from './width.js';
import { formatMoney } from '../core/cost.js';
const fmtTokens = (n) => (n < 1000 ? `${Math.round(n)}` : formatTokens(n));
function formatBytes(bytes) {
    if (bytes < 1024)
        return `${bytes} B`;
    if (bytes < 1024 * 1024)
        return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}
const pct = (part, whole) => (whole > 0 ? (part / whole) * 100 : 0);
function formatPct(value) {
    if (value === 0)
        return '0%';
    if (value < 1)
        return '<1%';
    return `${Math.round(value)}%`;
}
function segmentsOf(c) {
    return [
        { key: 'system', label: 'System prompt', tokens: c.system, color: palette.accent },
        { key: 'tools', label: `Tool schemas (${c.toolCount})`, tokens: c.tools, color: palette.plan },
        { key: 'user', label: 'Your messages', tokens: c.user, color: palette.ok },
        { key: 'assistant', label: 'Model replies', tokens: c.assistant, color: palette.text },
        { key: 'tool', label: 'Tool results', tokens: c.toolResults, color: palette.warn },
        { key: 'images', label: c.imageCount > 0 ? `Images (${c.imageCount})` : 'Images', tokens: c.images, color: palette.yolo },
    ];
}
/**
 * A bar of `width` cells, one coloured run per kind of content. Every non-empty kind gets at
 * least one cell, so a small share does not disappear.
 */
export function renderBar(segments, limit, width) {
    const cells = segments.map((s) => (s.tokens > 0 ? Math.max(1, Math.round((s.tokens / Math.max(1, limit)) * width)) : 0));
    let used = cells.reduce((a, b) => a + b, 0);
    // Rounding up the small ones can overshoot: take the cells back from the biggest.
    while (used > width) {
        const biggest = cells.indexOf(Math.max(...cells));
        if (cells[biggest] <= 1)
            break;
        cells[biggest]--;
        used--;
    }
    let bar = '';
    segments.forEach((s, i) => {
        if (cells[i] > 0)
            bar += s.color('█'.repeat(cells[i]));
    });
    return bar + palette.faint('░'.repeat(Math.max(0, width - used)));
}
/** How the cost number is to be read, in a few words. */
function costNote(session) {
    const st = session.costState;
    if ((st.unpriced ?? 0) > 0 && (st.priced ?? 0) === 0 && session.totalCost === 0)
        return 'no price known for this model';
    const parts = [];
    if (st.estimated)
        parts.push('catalogue prices, an estimate');
    if ((st.unpriced ?? 0) > 0)
        parts.push(`${st.unpriced} request${st.unpriced === 1 ? '' : 's'} not priced`);
    return parts.join(', ');
}
/** The content as rows of text, without the frame. `iw` is the number of cells available. */
function buildRows(d, iw, detail) {
    const rows = [];
    const line = (text) => rows.push({ kind: 'line', text });
    const title = (text) => rows.push({ kind: 'title', text });
    const blank = () => rows.push({ kind: 'blank', text: '' });
    const pair = (label, value, labelWidth = 16) => {
        const room = Math.max(4, Math.min(labelWidth, iw - 3 - displayWidth(value)));
        const left = '  ' + palette.muted(padEndWidth(truncateWidth(label, room), room));
        const gap = Math.max(1, iw - displayWidth(left) - displayWidth(value));
        line(left + ' '.repeat(gap) + value);
    };
    const c = d.context;
    const s = d.session;
    const usedPct = pct(c.total, c.limit);
    const usedColor = usedPct >= 85 ? palette.err : usedPct >= 70 ? palette.warn : palette.text;
    // ── context
    title('CONTEXT');
    const summary = `${formatPct(usedPct)}  ${fmtTokens(c.total)} / ${fmtTokens(c.limit)}`;
    const barWidth = Math.max(4, iw - 4 - displayWidth(summary) - 2);
    line('  ' + renderBar(segmentsOf(c), c.limit, barWidth) + '  ' + usedColor(summary));
    if (detail === 'full') {
        const labelWidth = Math.max(6, Math.min(22, iw - 19));
        for (const seg of segmentsOf(c)) {
            if (seg.tokens === 0 && seg.key === 'images')
                continue;
            const left = '  ' + seg.color('■') + ' ' + (seg.tokens > 0 ? palette.text(padEndWidth(truncateWidth(seg.label, labelWidth), labelWidth)) : palette.faint(padEndWidth(truncateWidth(seg.label, labelWidth), labelWidth)));
            const right = palette.text(fmtTokens(seg.tokens).padStart(6)) + palette.faint(('  ' + formatPct(pct(seg.tokens, c.limit))).padStart(6));
            line(left + ' '.repeat(Math.max(1, iw - displayWidth(left) - displayWidth(right))) + right);
        }
        const freeLeft = '  ' + palette.faint('□') + ' ' + palette.faint(padEndWidth('Free', labelWidth));
        const freeRight = palette.faint(fmtTokens(c.free).padStart(6) + ('  ' + formatPct(pct(c.free, c.limit))).padStart(6));
        line(freeLeft + ' '.repeat(Math.max(1, iw - displayWidth(freeLeft) - displayWidth(freeRight))) + freeRight);
    }
    if (detail === 'full' && d.limitSource) {
        const text = {
            catalogue: 'Window size: from the models.dev catalogue.',
            table: "Window size: from devx's table of models.",
            rule: 'Window size: guessed from the model name.',
            default: 'Window size: assumed, the model is unknown. Set contextLimits in the config.',
            config: 'Window size: from contextLimits in your config.',
            saved: 'Window size: saved when you chose the model.',
        };
        const warn = d.limitSource === 'default' || d.limitSource === 'rule';
        line('  ' + (warn ? palette.warn : palette.faint)(truncateWidth(text[d.limitSource], iw - 2)));
    }
    if (detail !== 'min') {
        line('  ' + palette.faint(truncateWidth(`Old messages are dropped above ${fmtTokens(c.compactAt)} (85%).`, iw - 2)));
        if (d.compactions > 0) {
            line('  ' + palette.faint(truncateWidth(`Happened ${d.compactions}x, ${d.dropped} message${d.dropped === 1 ? '' : 's'} dropped so far.`, iw - 2)));
        }
    }
    if (detail === 'full' && s.lastPromptTokens > 0) {
        line('  ' + palette.faint(truncateWidth(`Last request: the provider counted ${fmtTokens(s.lastPromptTokens)} input.`, iw - 2)));
    }
    if (d.noVision && c.imageCount > 0 && detail !== 'min')
        line('  ' + palette.warn(truncateWidth('This model cannot read images: they are not sent.', iw - 2)));
    // ── session
    if (detail !== 'min')
        blank();
    title('SESSION');
    pair('Cost', palette.bold(formatMoney(s.totalCost, s.costState)));
    const note = costNote(s);
    if (note && detail === 'full')
        line('  ' + palette.faint(truncateWidth(note, iw - 2)));
    const tokens = iw >= 40 ? `${fmtTokens(s.promptTokens)} in · ${fmtTokens(s.completionTokens)} out` : `${fmtTokens(s.promptTokens)}/${fmtTokens(s.completionTokens)}`;
    pair('Tokens', palette.text(tokens));
    if (s.cacheReadTokens > 0 && detail !== 'min')
        pair('Cached input', palette.text(fmtTokens(s.cacheReadTokens)));
    pair('Requests', palette.text(String(s.requestsCount)));
    // ── traffic
    if (detail === 'full') {
        blank();
        title('TRAFFIC');
        pair('Sent / received', palette.text(iw >= 40 ? `${formatBytes(s.bytesSent)} / ${formatBytes(s.bytesReceived)}` : `${formatBytes(s.bytesSent)}/${formatBytes(s.bytesReceived)}`));
        if (s.dataSaverLimitMB) {
            const used = s.totalBytes / (1024 * 1024);
            const share = Math.min(100, Math.round((used / s.dataSaverLimitMB) * 100));
            const text = `${used.toFixed(1)} / ${s.dataSaverLimitMB} MB (${share}%)`;
            pair('Data saver', share >= 90 ? palette.err(text) : palette.text(text));
        }
    }
    return rows;
}
function keyHints(width) {
    const variants = ['esc close', 'esc'];
    for (const text of variants) {
        const [key, ...rest] = text.split(' ');
        const out = palette.text(key) + (rest.length ? ' ' + palette.faint(rest.join(' ')) : '');
        if (displayWidth(out) <= width)
            return out;
    }
    return '';
}
function renderCard(d, cols, rows) {
    const W = Math.min(cols - 1, 76);
    const iw = W - 4;
    const rule = palette.rule;
    const lines = [];
    const box = (content) => rule('│') + ' ' + padEndWidth(content, iw) + ' ' + rule('│');
    const heading = ' ' + palette.bold(palette.accent('usage')) + ' ';
    const fmt = d.format === 'anthropic' ? 'anthropic' : 'openai';
    const modelText = truncateWidth(`${d.model}${d.effort ? ` · ${d.effort}` : ''} · ${fmt}`, Math.max(8, W - 4 - displayWidth(heading) - 4));
    const tag = ' ' + palette.faint(modelText) + ' ';
    const fill = Math.max(0, W - 4 - displayWidth(heading) - displayWidth(tag));
    lines.push(rule('╭─') + heading + rule('─'.repeat(fill)) + tag + rule('─╮'));
    const body = buildRows(d, iw, 'full');
    lines.push(box(''));
    for (const row of body) {
        if (row.kind === 'title')
            lines.push(box('  ' + palette.faint(row.text)));
        else if (row.kind === 'blank')
            lines.push(box(''));
        else
            lines.push(box(row.text));
    }
    lines.push(box(''));
    lines.push(rule('╰' + '─'.repeat(W - 2) + '╯'));
    lines.push(' ' + keyHints(cols - 2));
    return lines.length <= rows - 1 ? lines : null;
}
function renderPlain(d, cols, rows) {
    const width = Math.max(20, cols - 1);
    const head = ' ' + palette.bold(palette.accent('usage')) + palette.faint(' · ' + truncateWidth(d.model, Math.max(6, width - 10)));
    for (const detail of ['full', 'brief', 'min']) {
        const body = buildRows(d, width - 1, detail);
        const lines = [head, ...body.map((r) => (r.kind === 'title' ? ' ' + palette.faint(r.text) : r.kind === 'blank' ? '' : ' ' + r.text)), ' ' + keyHints(width - 1)];
        if (lines.length <= rows - 1 || detail === 'min') {
            // On a very short screen drop the blank rows too.
            return lines.length <= rows - 1 ? lines : lines.filter((l) => l !== '');
        }
    }
    return [head];
}
/** The whole panel as terminal rows: a framed card on a wide screen, a plain list on a narrow or short one. */
export function renderUsagePanel(d, cols, rows) {
    if (cols >= 60) {
        const card = renderCard(d, cols, rows);
        if (card)
            return card;
    }
    return renderPlain(d, cols, rows);
}
/** Shows the panel until Esc (or q, Enter, Ctrl+C) is pressed. The panel is erased on close. */
export function runUsagePanel(data, options = {}) {
    return new Promise((resolve) => {
        const { input, output } = options.io ?? { input: process.stdin, output: process.stdout };
        const block = new LiveBlock(output);
        const draw = () => block.draw(renderUsagePanel(data, output.columns || 80, output.rows || 30));
        const wasRaw = !!input.isRaw;
        input.setRawMode?.(true);
        input.resume();
        let closed = false;
        const close = () => {
            if (closed)
                return;
            closed = true;
            input.off('data', onData);
            output.off?.('resize', draw);
            block.erase();
            if (!wasRaw)
                input.setRawMode?.(false);
            resolve();
        };
        const onData = (chunk) => {
            for (const key of splitKeys(chunk.toString())) {
                if (closed)
                    return;
                if (key === '\x1b' || key === 'q' || key === '\r' || key === '\n' || key === ' ' || key === '\x03')
                    return close();
            }
        };
        input.on('data', onData);
        output.on?.('resize', draw);
        draw();
    });
}
