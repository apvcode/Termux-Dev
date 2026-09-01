import pc from 'picocolors';
import { getCurrentTheme } from './theme.js';
export function renderBannerLines(mode = 'full', version = '1.5.0', cols = 80) {
    if (mode === 'off') {
        return [];
    }
    const theme = getCurrentTheme();
    if (mode === 'minimal') {
        return [
            theme.colorFn('⚡ ') + theme.boldFn('TERMUX·DEV') + ' ' + pc.dim(`v${version}`) + pc.dim(' · Type /help for commands')
        ];
    }
    // Full ASCII Banner
    if (cols < 68) {
        // Compact ASCII for narrow mobile screens (~26 chars)
        return [
            theme.colorFn('  █▀▀▄ █▀▀▀ █   █ █   █'),
            theme.colorFn('  █  █ █▀▀▀  ▀▄▀   ▀▄▀ '),
            theme.colorFn('  █▄▄▀ █▄▄▄   ▀    ▀ ▀ '),
            '  ' + theme.boldFn(`v${version}`)
        ];
    }
    // Full wide ASCII banner (53 chars)
    const indent = '   ';
    return [
        indent + theme.colorFn('▀▀▀█▀▀▀ █▀▀▀ █▀▀█ █▄ ▄█ █  █ ▀▄ ▄▀    █▀▀▄ █▀▀▀ █   █'),
        indent + theme.colorFn('   █    █▀▀▀ █▄▄▀ █ █ █ █  █   █   ▀▀ █  █ █▀▀▀ █   █'),
        indent + theme.colorFn('   █    █▄▄▄ █ ▀▄ █   █ ▀▄▄▀ ▄▀ ▀▄    █▄▄▀ █▄▄▄  ▀▄▀ '),
        indent + theme.boldFn(`v${version}`)
    ];
}
export function drawBanner(mode = 'full', version = '1.5.0', cols = 80) {
    const lines = renderBannerLines(mode, version, cols);
    if (lines.length > 0) {
        console.log();
        for (const l of lines) {
            console.log(l);
        }
        console.log();
    }
}
