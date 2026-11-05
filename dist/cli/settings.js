import { LiveBlock } from './liveblock.js';
import { splitKeys } from './keys.js';
import { THEME_IDS, getTheme, renderSwatch, renderThemePreview } from './theme.js';
import { palette } from './ui.js';
import { displayWidth, padEndWidth, stripAnsi, truncateWidth, wrapWords } from './width.js';
export { wrapWords };
/**
 * The settings screen: one panel that is redrawn in place (nothing piles up in the
 * scrollback), moves with the arrow keys without wrapping around at the ends, and applies
 * every change immediately, so a theme can be judged on the panel itself.
 *
 * Rendering is a pure function of the state; `runSettings` only adds the keyboard.
 */
export const ITERATION_STEPS = [10, 20, 30, 50, 100, 200, 500, 9999];
export const ROWS = [
    { key: 'theme', section: 'Appearance', label: 'Theme', short: 'Theme', kind: 'choice' },
    { key: 'layout', section: 'Appearance', label: 'Interface', short: 'Interface', kind: 'choice' },
    { key: 'pureBlack', section: 'Appearance', label: 'Black background', short: 'Black bg', kind: 'toggle' },
    { key: 'autoApprove', section: 'Agent', label: 'Auto-approve (YOLO)', short: 'YOLO mode', kind: 'toggle' },
    { key: 'maxIterations', section: 'Agent', label: 'Max iterations', short: 'Iterations', kind: 'choice' },
    { key: 'memory', section: 'Agent', label: 'Project memory', short: 'Memory', kind: 'toggle' },
    { key: 'updates', section: 'Agent', label: 'Update check', short: 'Updates', kind: 'toggle' },
    { key: 'about', section: 'About', label: 'About devx', short: 'About', kind: 'info' },
];
const HINTS = {
    theme: 'Colours of the whole interface. Left and right browse the themes, the panel recolours as you go.',
    layout: 'Clean: framed prompt, cards and quiet tool lines. Classic: the original ASCII layout.',
    pureBlack: 'Sets the terminal background to deep OLED black (#0a0a0c). Off keeps your own background.',
    autoApprove: 'Skips routine permission prompts. Dangerous commands and credential files still ask first.',
    maxIterations: 'How many tool steps (file edits, commands) the agent may take for one request.',
    memory: 'Loads the rules from .devx/memory.md into every session.',
    updates: 'Looks for a new devx version on GitHub at startup. /update works either way.',
    about: 'Terminal-native AI coding agent by ApvCode. MIT license. github.com/apvcode/Termux-Dev',
};
function cycle(items, current, direction) {
    const index = items.indexOf(current);
    if (index === -1)
        return items[direction === 1 ? 0 : items.length - 1];
    return items[(index + direction + items.length) % items.length];
}
/** Closest step of the iteration list, for values set by hand in the config file. */
function nearestStep(value) {
    return ITERATION_STEPS.reduce((best, step) => (Math.abs(step - value) < Math.abs(best - value) ? step : best), ITERATION_STEPS[0]);
}
/**
 * Applies one key press to a setting. Left/right move through the values of a choice;
 * toggles flip whichever way you press. The input state is never modified.
 */
export function changeSetting(state, key, direction) {
    switch (key) {
        case 'theme':
            return { ...state, themeId: cycle(THEME_IDS, getTheme(state.themeId).id, direction) };
        case 'layout':
            return { ...state, layout: state.layout === 'clean' ? 'classic' : 'clean' };
        case 'maxIterations': {
            const current = ITERATION_STEPS.includes(state.maxIterations) ? state.maxIterations : nearestStep(state.maxIterations);
            return { ...state, maxIterations: cycle(ITERATION_STEPS, current, direction) };
        }
        case 'pureBlack':
            return { ...state, pureBlack: !state.pureBlack };
        case 'autoApprove':
            return { ...state, autoApprove: !state.autoApprove };
        case 'memory':
            return { ...state, memory: !state.memory };
        case 'updates':
            return { ...state, updates: !state.updates };
        default:
            return state;
    }
}
/** Moves the focus one row. It stops at the first and the last row instead of wrapping. */
export function moveFocus(focus, delta) {
    return Math.max(0, Math.min(ROWS.length - 1, focus + delta));
}
// ───────────────────────── rendering ─────────────────────────
const LABEL_WIDTH = 21;
const SHORT_LABEL_WIDTH = 11;
function formatIterations(n) {
    return n >= 9999 ? 'unlimited' : `${n} steps`;
}
function onOff(on, onColor = palette.ok, glyph = '●') {
    return on ? onColor(`${glyph} on`) : palette.faint('○ off');
}
/** The current value of a row, styled; `room` is the number of cells available. */
function valueText(key, state, version, room, withSwatch) {
    switch (key) {
        case 'theme': {
            const theme = getTheme(state.themeId);
            const swatch = withSwatch ? renderSwatch(theme) + '  ' : theme.tokens.accent(theme.symbol) + ' ';
            const used = withSwatch ? 9 : 2;
            return swatch + palette.text(truncateWidth(theme.name, Math.max(4, room - used)));
        }
        case 'layout':
            return palette.text(state.layout === 'clean' ? 'Clean' : 'Classic');
        case 'pureBlack':
            return onOff(state.pureBlack);
        case 'autoApprove':
            return onOff(state.autoApprove, palette.yolo, '▲');
        case 'maxIterations':
            return palette.text(formatIterations(state.maxIterations));
        case 'memory':
            return onOff(state.memory);
        case 'updates':
            return onOff(state.updates);
        case 'about':
            return palette.muted(`v${version}`);
    }
}
/** Help text under the list: what the focused setting does, plus a live preview for themes. */
function detailLines(state, key, width, maxLines, version) {
    if (maxLines <= 0)
        return [];
    if (key === 'theme') {
        const theme = getTheme(state.themeId);
        // The sample lines are styled; on a very narrow screen they are cut (and lose their colour).
        const preview = renderThemePreview(theme).split('\n').map((line) => (displayWidth(line) <= width ? line : truncateWidth(stripAnsi(line), width)));
        return [palette.muted(truncateWidth(`${theme.name}: ${theme.desc}`, width)), ...preview].slice(0, maxLines);
    }
    const hint = key === 'about' ? `devx v${version}. ${HINTS.about}` : HINTS[key];
    // A warning is never the line that gets cut off: it keeps its row, the explanation gives way.
    const warning = key === 'autoApprove' && state.autoApprove ? 'On: the agent runs commands and edits files without asking.' : '';
    const hintRows = Math.max(1, maxLines - (warning ? 1 : 0));
    let wrapped = wrapWords(hint, width);
    if (wrapped.length > hintRows) {
        wrapped = wrapped.slice(0, hintRows);
        wrapped[hintRows - 1] = truncateWidth(wrapped[hintRows - 1], width - 1).replace(/…$/, '') + '…';
    }
    const out = wrapped.map((line) => palette.muted(line));
    if (warning && out.length < maxLines)
        out.push(palette.yolo(truncateWidth(warning, width)));
    return out;
}
/** The key hints, shortened step by step until they fit `width` cells. */
function keyHints(width) {
    const variants = [
        [['↑↓', 'move'], ['←→', 'change'], ['enter', 'toggle'], ['esc', 'close']],
        [['↑↓', 'move'], ['←→', 'change'], ['esc', 'close']],
        [['↑↓', ''], ['←→', ''], ['esc', '']],
        [['esc', '']],
    ];
    for (const items of variants) {
        const text = items.map(([k, label]) => palette.text(k) + (label ? ' ' + palette.faint(label) : '')).join(palette.faint(items[0][1] ? ' · ' : ' '));
        if (displayWidth(text) <= width)
            return text;
    }
    return '';
}
function renderCard(state, focus, cols, density, version) {
    const W = Math.min(cols - 1, 76);
    const iw = W - 4;
    const rule = palette.rule;
    const maxDetail = density === 'roomy' ? 5 : 4;
    const lines = [];
    const box = (content, shaded = false) => rule('│') + (shaded ? palette.shade(' ' + padEndWidth(content, iw) + ' ') : ' ' + padEndWidth(content, iw) + ' ') + rule('│');
    const title = ' ' + palette.bold(palette.accent('settings')) + ' ';
    const tag = ' ' + palette.faint(`devx v${version}`) + ' ';
    const fill = Math.max(0, W - 4 - displayWidth(title) - displayWidth(tag));
    lines.push(rule('╭─') + title + rule('─'.repeat(fill)) + tag + rule('─╮'));
    if (density === 'roomy')
        lines.push(box(''));
    let section = '';
    ROWS.forEach((row, index) => {
        if (row.section !== section) {
            if (section !== '' && density === 'roomy')
                lines.push(box(''));
            section = row.section;
            lines.push(box('  ' + palette.faint(row.section.toUpperCase())));
        }
        const focused = index === focus;
        const mark = focused ? palette.accent('❯') : ' ';
        const label = focused ? palette.bold(palette.text(row.label)) : palette.muted(row.label);
        const control = row.kind === 'choice' ? (focused ? palette.accent('‹ ›') : palette.faint('‹ ›')) : '';
        const prefix = ` ${mark} ${padEndWidth(label, LABEL_WIDTH)}`;
        const room = iw - displayWidth(prefix) - 4;
        const left = prefix + valueText(row.key, state, version, room, true);
        const gap = Math.max(1, iw - displayWidth(left) - displayWidth(control) - 1);
        lines.push(box(left + ' '.repeat(gap) + control, focused));
    });
    if (density === 'roomy')
        lines.push(box(''));
    lines.push(rule('├' + '─'.repeat(W - 2) + '┤'));
    // The detail area always has the same height, so the panel does not jump while you move.
    const detail = detailLines(state, ROWS[focus].key, iw - 2, maxDetail, version);
    for (let i = 0; i < maxDetail; i++)
        lines.push(box(detail[i] ? '  ' + detail[i] : ''));
    lines.push(rule('╰' + '─'.repeat(W - 2) + '╯'));
    lines.push(' ' + keyHints(cols - 2));
    return lines;
}
function renderCompact(state, focus, cols, rows, version) {
    const width = Math.max(20, cols - 1);
    const lines = [];
    lines.push(' ' + palette.bold(palette.accent('settings')) + palette.faint(` · v${version}`));
    ROWS.forEach((row, index) => {
        const focused = index === focus;
        const mark = focused ? palette.accent('❯') : ' ';
        const label = focused ? palette.bold(palette.text(row.short)) : palette.muted(row.short);
        const prefix = ` ${mark} ${padEndWidth(label, SHORT_LABEL_WIDTH)}`;
        const room = Math.max(4, width - displayWidth(prefix) - 1);
        const content = prefix + valueText(row.key, state, version, room, false);
        lines.push(focused ? palette.shade(padEndWidth(content, width)) : content);
    });
    const detailCount = rows >= 16 ? 2 : 1;
    const detail = detailLines(state, ROWS[focus].key, width - 2, detailCount, version);
    for (let i = 0; i < detailCount; i++)
        lines.push(detail[i] ? ' ' + detail[i] : '');
    lines.push(' ' + keyHints(width - 1));
    return lines;
}
/**
 * The whole panel as terminal rows. Wide terminals get a framed card (roomy or tight
 * depending on the height), narrow ones a plain list.
 */
export function renderSettings(state, focus, cols, rows, version) {
    if (cols < 60 || rows < 21)
        return renderCompact(state, focus, cols, rows, version);
    return renderCard(state, focus, cols, rows >= 27 ? 'roomy' : 'tight', version);
}
/**
 * Shows the panel and resolves with the final state when it is closed (Esc, q or Ctrl+C).
 * The panel is erased on close.
 */
export function runSettings(initial, options) {
    return new Promise((resolve) => {
        const { input, output } = options.io ?? { input: process.stdin, output: process.stdout };
        const block = new LiveBlock(output);
        let state = { ...initial };
        let focus = 0;
        const draw = () => block.draw(renderSettings(state, focus, output.columns || 80, output.rows || 30, options.version));
        const wasRaw = !!input.isRaw;
        input.setRawMode?.(true);
        input.resume();
        const change = (direction) => {
            const next = changeSetting(state, ROWS[focus].key, direction);
            if (next === state)
                return;
            state = next;
            options.onChange?.(ROWS[focus].key, state);
            draw();
        };
        const close = () => {
            input.off('data', onData);
            block.erase();
            if (!wasRaw)
                input.setRawMode?.(false);
            resolve(state);
        };
        let closed = false;
        const handleKey = (key) => {
            const move = (delta) => {
                const next = moveFocus(focus, delta);
                if (next !== focus) {
                    focus = next;
                    draw();
                }
            };
            if (key === '\x1b[A' || key === '\x1bOA' || key === 'k')
                return move(-1);
            if (key === '\x1b[B' || key === '\x1bOB' || key === 'j')
                return move(1);
            // Home / End are sent differently by different terminals.
            if (key === '\x1b[H' || key === '\x1b[1~' || key === '\x1b[7~' || key === '\x1bOH' || key === 'g')
                return move(-ROWS.length);
            if (key === '\x1b[F' || key === '\x1b[4~' || key === '\x1b[8~' || key === '\x1bOF' || key === 'G')
                return move(ROWS.length);
            if (key === '\x1b[C' || key === '\x1bOC' || key === 'l' || key === ' ' || key === '\r' || key === '\n')
                return change(1);
            if (key === '\x1b[D' || key === '\x1bOD' || key === 'h')
                return change(-1);
            if (key === '\x1b' || key === 'q' || key === '\x03') {
                closed = true;
                return close();
            }
        };
        // Several key presses can arrive in one chunk; handle them one by one.
        const onData = (chunk) => {
            for (const key of splitKeys(chunk.toString())) {
                if (closed)
                    return;
                handleKey(key);
            }
        };
        input.on('data', onData);
        draw();
    });
}
