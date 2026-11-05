import pc from 'picocolors';
/**
 * Colour helpers for themes.
 *
 * Themes are defined as hex values. On a truecolor terminal they are used exactly; on a
 * 256-colour terminal (older Termux builds, ssh into a plain xterm) each colour is mapped to
 * the closest entry of the xterm palette. With colours switched off everything is plain text.
 */
export function hexToRgb(hex) {
    const h = hex.replace('#', '');
    const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h;
    return [parseInt(full.slice(0, 2), 16), parseInt(full.slice(2, 4), 16), parseInt(full.slice(4, 6), 16)];
}
const CUBE_LEVELS = [0, 95, 135, 175, 215, 255];
const distance = (a, b) => (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2;
/** Closest xterm-256 palette index for an RGB colour (colour cube or grayscale ramp). */
export function rgbTo256(r, g, b) {
    const nearestLevel = (v) => CUBE_LEVELS.reduce((best, level, i) => (Math.abs(level - v) < Math.abs(CUBE_LEVELS[best] - v) ? i : best), 0);
    const ri = nearestLevel(r);
    const gi = nearestLevel(g);
    const bi = nearestLevel(b);
    const cubeIndex = 16 + 36 * ri + 6 * gi + bi;
    const cubeRgb = [CUBE_LEVELS[ri], CUBE_LEVELS[gi], CUBE_LEVELS[bi]];
    const average = (r + g + b) / 3;
    const grayStep = Math.max(0, Math.min(23, Math.round((average - 8) / 10)));
    const grayValue = 8 + 10 * grayStep;
    const grayIndex = 232 + grayStep;
    const target = [r, g, b];
    return distance(target, [grayValue, grayValue, grayValue]) < distance(target, cubeRgb) ? grayIndex : cubeIndex;
}
export function supportsTruecolor(env = process.env) {
    const forced = (env.DEVX_COLOR || '').toLowerCase();
    if (forced === '256')
        return false;
    if (forced === 'truecolor' || forced === '24bit')
        return true;
    const colorterm = (env.COLORTERM || '').toLowerCase();
    return colorterm.includes('truecolor') || colorterm.includes('24bit');
}
function sgr(hex, base, opts) {
    const [r, g, b] = hexToRgb(hex);
    const truecolor = opts.truecolor ?? supportsTruecolor();
    return truecolor ? `\x1b[${base};2;${r};${g};${b}m` : `\x1b[${base};5;${rgbTo256(r, g, b)}m`;
}
/** Foreground colour from a hex value. */
export function fgHex(hex, opts = {}) {
    return (text) => ((opts.enabled ?? pc.isColorSupported) ? `${sgr(hex, 38, opts)}${text}\x1b[39m` : text);
}
/** Background colour from a hex value. */
export function bgHex(hex, opts = {}) {
    return (text) => ((opts.enabled ?? pc.isColorSupported) ? `${sgr(hex, 48, opts)}${text}\x1b[49m` : text);
}
/** WCAG relative luminance of a hex colour. */
export function relativeLuminance(hex) {
    const channel = (v) => {
        const c = v / 255;
        return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    };
    const [r, g, b] = hexToRgb(hex);
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}
/** WCAG contrast ratio between two hex colours (1 to 21). */
export function contrastRatio(a, b) {
    const la = relativeLuminance(a);
    const lb = relativeLuminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}
