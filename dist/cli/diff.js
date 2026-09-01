import pc from 'picocolors';
import { getCurrentTheme } from './theme.js';
export function formatDiffBox(fileName, diffLines, cols = 80, summary, diffColors = false) {
    const theme = getCurrentTheme();
    const boxWidth = Math.max(30, Math.min(cols - 4, 76));
    const cleanFileName = typeof fileName === 'string' ? fileName.split(/[\/\\]/).pop() || 'file' : 'file';
    const fillCount = Math.max(2, boxWidth - 5 - cleanFileName.length);
    const lines = [
        theme.colorFn('┌─ ') + pc.bold(cleanFileName) + ' ' + theme.colorFn('─'.repeat(fillCount) + '┐')
    ];
    const maxShown = Math.min(diffLines.length, 30);
    for (let i = 0; i < maxShown; i++) {
        const rawLine = diffLines[i];
        const match = rawLine.match(/^(\d+)(\s+[+\- ]\s+)(.*)$/);
        if (match) {
            const lineNum = match[1].padStart(4, ' ');
            const symbol = match[2];
            const contentStr = match[3] || '';
            const isAdd = symbol.includes('+');
            const isRemove = symbol.includes('-');
            const prefix = isRemove ? '-' : isAdd ? '+' : ' ';
            const maxCodeLen = Math.max(10, boxWidth - 11);
            const paddedCode = contentStr.length > maxCodeLen
                ? contentStr.substring(0, maxCodeLen - 1) + '…'
                : contentStr.padEnd(maxCodeLen, ' ');
            const innerRow = ` ${lineNum} ${prefix} ${paddedCode} `;
            if (diffColors) {
                if (isRemove) {
                    lines.push(theme.colorFn('│') + pc.red(innerRow) + theme.colorFn('│'));
                }
                else if (isAdd) {
                    lines.push(theme.colorFn('│') + pc.green(innerRow) + theme.colorFn('│'));
                }
                else {
                    lines.push(theme.colorFn('│') + pc.dim(innerRow) + theme.colorFn('│'));
                }
            }
            else {
                if (isRemove || isAdd) {
                    lines.push(theme.colorFn('│') + pc.white(innerRow) + theme.colorFn('│'));
                }
                else {
                    lines.push(theme.colorFn('│') + pc.dim(innerRow) + theme.colorFn('│'));
                }
            }
        }
        else {
            const maxLen = Math.max(10, boxWidth - 4);
            const padded = rawLine.length > maxLen ? rawLine.substring(0, maxLen - 1) + '…' : rawLine.padEnd(maxLen, ' ');
            lines.push(theme.colorFn('│ ') + pc.white(padded) + theme.colorFn(' │'));
        }
    }
    if (diffLines.length > maxShown) {
        const dots = `... +${diffLines.length - maxShown} more lines`;
        const maxLen = Math.max(10, boxWidth - 4);
        const paddedDots = dots.length > maxLen ? dots.substring(0, maxLen - 1) + '…' : dots.padEnd(maxLen, ' ');
        lines.push(theme.colorFn('│ ') + pc.dim(paddedDots) + theme.colorFn(' │'));
    }
    lines.push(theme.colorFn('└' + '─'.repeat(boxWidth - 2) + '┘'));
    if (summary) {
        lines.push(theme.boldFn(`  └─ ${summary}`));
    }
    return lines.join('\n');
}
