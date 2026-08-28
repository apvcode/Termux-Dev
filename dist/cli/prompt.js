import pc from 'picocolors';
import { scanProjectFiles } from './files.js';
import { saveClipboardImage, processPastedFilePath } from './clipboard.js';
import { getCurrentTheme, listThemes } from './theme.js';
import { CustomCommandManager } from '../core/commands.js';
export const SLASH_COMMANDS = [
    { cmd: '/new', desc: 'Start a new clean chat session' },
    { cmd: '/resume', desc: 'Resume a previous chat session' },
    { cmd: '/session', desc: 'Show active session ID, stats, and info' },
    { cmd: '/session del', desc: 'Select and delete saved sessions' },
    { cmd: '/usage', desc: 'Show network bandwidth, data saver & token cost' },
    { cmd: '/export', desc: 'Export session conversation to Markdown' },
    { cmd: '/mcp', desc: 'Manage Model Context Protocol (MCP) servers & tools' },
    { cmd: '/theme', desc: 'Switch UI theme (Cyan, Purple, Matrix, Amber, etc.)' },
    { cmd: '/doctor', desc: 'Run system & environment health diagnostics' },
    { cmd: '/settings', desc: 'Configure permissions & auto-approval' },
    { cmd: '/update', desc: 'Check and install updates from GitHub' },
    { cmd: '/model', desc: 'Switch model for current provider' },
    { cmd: '/provider', desc: 'Switch AI provider (OpenRouter, Google, etc.)' },
    { cmd: '/plan', desc: 'Switch to PLAN mode (architect & planner)' },
    { cmd: '/agent', desc: 'Switch to AGENT mode (coder & executor)' },
    { cmd: '/image', desc: 'Paste image from clipboard as [1.png 203kb]' },
    { cmd: '/serve', desc: 'Start local web server for web/game preview' },
    { cmd: '/memory', desc: 'View, add, or clear project memory bank' },
    { cmd: '/undo', desc: 'Revert last file changes made by AI' },
    { cmd: '/diff', desc: 'Show git diff of modified project files' },
    { cmd: '/commit', desc: 'AI-generated git commit message & commit' },
    { cmd: '/status', desc: 'Show git repository file status' },
    { cmd: '/compact', desc: 'Compact & summarize chat context' },
    { cmd: '/clear', desc: 'Clear screen & redraw banner' },
    { cmd: '/init', desc: 'Generate AGENTS.md project instructions' },
    { cmd: '/config', desc: 'View current configuration' },
    { cmd: '/help', desc: 'Show all available commands' },
    { cmd: '/exit', desc: 'Exit devx' }
];
const GLOBAL_PROMPT_HISTORY = [];
export function askPrompt(opts = {}) {
    return new Promise((resolve) => {
        process.stdin.resume();
        const msg = opts.message ? `${opts.message} ${pc.dim(`(Tab = ${opts.planMode ? 'AGENT' : 'PLAN'})`)}` : `Ask anything... ${pc.dim(`(Tab = ${opts.planMode ? 'AGENT' : 'PLAN'})`)}`;
        const placeholder = opts.placeholder || 'Describe a task, @file, /help, paste image, or press Tab to switch mode';
        let input = opts.initialValue || '';
        let cursorPos = input.length;
        let selectedIndex = 0;
        let lastDropdownLines = 0;
        let disposed = false;
        let historyIndex = GLOBAL_PROMPT_HISTORY.length;
        let tempDraft = '';
        let availableFiles = [];
        let customCommandsList = [];
        // Preload project files and custom slash commands
        scanProjectFiles().then(files => {
            availableFiles = files;
        }).catch(() => { });
        CustomCommandManager.listCommands().then(cmds => {
            customCommandsList = cmds.map(c => ({ cmd: c.cmd, desc: c.desc }));
        }).catch(() => { });
        const pastes = [];
        const imageAttachments = [];
        const usedImageNames = new Set();
        const theme = getCurrentTheme();
        // Header printed once
        console.log(theme.colorFn('◆') + '  ' + pc.bold(msg));
        if (process.stdin.isTTY) {
            process.stdin.setRawMode(true);
        }
        if (process.stdout.isTTY) {
            process.stdout.write('\x1b[?2004h');
        }
        function getDropdownItems() {
            if (input.startsWith('/theme ') || input.startsWith('/themes ') || input === '/theme') {
                const afterCmd = input.replace(/^\/(?:theme|themes)\s*/i, '').trim().toLowerCase();
                const themes = listThemes();
                const matched = themes.filter(t => !afterCmd ||
                    t.id.toLowerCase().startsWith(afterCmd) ||
                    t.name.toLowerCase().includes(afterCmd));
                const list = [];
                if (!afterCmd || '/theme'.startsWith(input.trim().toLowerCase())) {
                    list.push({
                        label: '/theme',
                        desc: 'Interactive UI theme picker menu',
                        replacement: '/theme',
                        replaceStart: 0,
                        replaceLen: input.length
                    });
                }
                for (const t of matched) {
                    list.push({
                        label: `/theme ${t.id}`,
                        desc: `${t.emoji} ${t.name} (${t.desc})`,
                        replacement: `/theme ${t.id}`,
                        replaceStart: 0,
                        replaceLen: input.length
                    });
                }
                return list;
            }
            if (input.startsWith('/')) {
                const q = input.trim().toLowerCase();
                const baseList = [...SLASH_COMMANDS];
                for (const cc of customCommandsList) {
                    if (!baseList.some(b => b.cmd === cc.cmd)) {
                        baseList.push({ cmd: cc.cmd, desc: cc.desc });
                    }
                }
                const filtered = baseList.filter(c => c.cmd.toLowerCase().startsWith(q) || q === '/');
                return filtered.map(c => ({
                    label: c.cmd,
                    desc: c.desc,
                    replacement: c.cmd,
                    replaceStart: 0,
                    replaceLen: input.length
                }));
            }
            // Check for @ mention at cursor position
            const beforeCursor = input.slice(0, cursorPos);
            const atMatch = beforeCursor.match(/@([a-zA-Z0-9_\-./]*)$/);
            if (atMatch && availableFiles.length > 0) {
                const query = atMatch[1].toLowerCase();
                const matched = availableFiles
                    .filter(f => f.toLowerCase().includes(query) || query === '')
                    .slice(0, 8);
                return matched.map(f => ({
                    label: `@${f}`,
                    desc: f.endsWith('/') ? 'directory' : 'file',
                    replacement: `@${f}${f.endsWith('/') ? '' : ' '}`,
                    replaceStart: cursorPos - atMatch[0].length,
                    replaceLen: atMatch[0].length
                }));
            }
            return [];
        }
        function formatInputWithBadges(rawText) {
            let formatted = rawText;
            for (const p of pastes) {
                const styled = pc.bold(pc.cyan(`[Pasted text #${p.id} +${p.linesCount} lines]`));
                formatted = formatted.split(p.tag).join(styled);
            }
            for (const img of imageAttachments) {
                const styled = pc.bold(pc.magenta(img.tag));
                formatted = formatted.split(img.tag).join(styled);
            }
            return formatted;
        }
        function expandPastes(rawText) {
            let result = rawText;
            for (const p of pastes) {
                result = result.split(p.tag).join(p.content);
            }
            for (const img of imageAttachments) {
                result = result.split(img.tag).join(`@${img.filePath.replace(/\\/g, '/')} `);
            }
            return result;
        }
        function render() {
            if (disposed)
                return;
            const items = getDropdownItems();
            const dropdownLines = [];
            if (items.length > 0) {
                if (selectedIndex >= items.length)
                    selectedIndex = 0;
                if (selectedIndex < 0)
                    selectedIndex = items.length - 1;
                const boxWidth = Math.min((process.stdout.columns || 80) - 6, 60);
                const pageSize = Math.min(5, Math.max(3, Math.floor(((process.stdout.rows || 24) - 4) / 2)));
                const total = items.length;
                let startIndex = 0;
                if (total > pageSize) {
                    if (selectedIndex >= pageSize) {
                        startIndex = Math.min(selectedIndex - pageSize + 1, total - pageSize);
                    }
                }
                const endIndex = Math.min(startIndex + pageSize, total);
                const hasMoreUp = startIndex > 0;
                const hasMoreDown = endIndex < total;
                let topBorderStr = '─'.repeat(boxWidth);
                if (hasMoreUp) {
                    const mid = Math.max(0, Math.floor(boxWidth / 2) - 2);
                    topBorderStr = '─'.repeat(mid) + ' ▲ ' + '─'.repeat(Math.max(0, boxWidth - mid - 3));
                }
                let botBorderStr = '─'.repeat(boxWidth);
                if (hasMoreDown) {
                    const mid = Math.max(0, Math.floor(boxWidth / 2) - 2);
                    botBorderStr = '─'.repeat(mid) + ' ▼ ' + '─'.repeat(Math.max(0, boxWidth - mid - 3));
                }
                dropdownLines.push(pc.dim('│') + '  ' + pc.dim('╭' + topBorderStr + '╮'));
                for (let i = startIndex; i < endIndex; i++) {
                    const item = items[i];
                    const isSelected = i === selectedIndex;
                    const labelStr = item.label.length > 20 ? item.label.slice(0, 19) + '…' : item.label.padEnd(20);
                    const maxDescLen = Math.max(6, boxWidth - 25);
                    const descStr = item.desc.length > maxDescLen ? item.desc.slice(0, maxDescLen - 3) + '...' : item.desc.padEnd(maxDescLen);
                    let row = ` ${isSelected ? theme.colorFn('›') : ' '} ${isSelected ? theme.boldFn(labelStr) : pc.white(labelStr)} ${pc.gray(descStr)} `;
                    if (isSelected) {
                        row = theme.badgeFn(`› ${labelStr} ${descStr}`);
                    }
                    dropdownLines.push(pc.dim('│') + '  ' + pc.dim('│') + row + pc.dim('│'));
                }
                dropdownLines.push(pc.dim('│') + '  ' + pc.dim('╰' + botBorderStr + '╯'));
            }
            // 1. Draw/update input line (line 0)
            let inputDisplay = pc.dim('│') + '  ';
            if (input.length === 0) {
                inputDisplay += pc.dim(placeholder);
            }
            else {
                inputDisplay += formatInputWithBadges(input);
            }
            process.stdout.write(`\r\x1b[2K${inputDisplay}`);
            // 2. Draw dropdown lines below, and clear any leftover old lines
            const totalLinesToProcess = Math.max(dropdownLines.length, lastDropdownLines);
            if (totalLinesToProcess > 0) {
                for (let i = 0; i < totalLinesToProcess; i++) {
                    if (i < dropdownLines.length) {
                        process.stdout.write(`\n\x1b[2K${dropdownLines[i]}`);
                    }
                    else {
                        process.stdout.write(`\n\x1b[2K`);
                    }
                }
                // 3. Move cursor back UP to prompt line (line 0) at cursorPos column
                process.stdout.write(`\x1b[${totalLinesToProcess}A\r\x1b[${3 + cursorPos}C`);
            }
            else {
                process.stdout.write(`\r\x1b[${3 + cursorPos}C`);
            }
            lastDropdownLines = dropdownLines.length;
        }
        render();
        function cleanup() {
            disposed = true;
            if (process.stdout.isTTY) {
                process.stdout.write('\x1b[?2004l');
            }
            process.stdin.removeListener('data', onData);
            if (process.stdin.isTTY) {
                process.stdin.setRawMode(false);
            }
            process.stdin.pause();
        }
        function clearBoxAndExit(finalInput) {
            if (lastDropdownLines > 0) {
                for (let i = 0; i < lastDropdownLines; i++) {
                    process.stdout.write(`\n\x1b[2K`);
                }
                process.stdout.write(`\x1b[${lastDropdownLines}A\r`);
                lastDropdownLines = 0;
            }
            const fullText = expandPastes(finalInput);
            if (fullText.trim()) {
                if (GLOBAL_PROMPT_HISTORY.length === 0 || GLOBAL_PROMPT_HISTORY[GLOBAL_PROMPT_HISTORY.length - 1] !== fullText.trim()) {
                    GLOBAL_PROMPT_HISTORY.push(fullText.trim());
                }
            }
            const lines = fullText.split('\n');
            process.stdout.write(`\r\x1b[2K${pc.dim('│')}  ${formatInputWithBadges(lines[0])}\n`);
            for (let i = 1; i < lines.length; i++) {
                process.stdout.write(pc.dim('│') + '  ' + lines[i] + '\n');
            }
            process.stdout.write('\n');
            cleanup();
            resolve(fullText);
        }
        async function handlePaste(pastedContent) {
            const cleaned = pastedContent.replace(/\x1b\[200~/g, '').replace(/\x1b\[201~/g, '');
            // Check if pastedContent contains file path(s) to images
            const pathCandidates = cleaned.split(/[&;\n\r]+/).map(s => s.trim()).filter(Boolean);
            let foundAnyImage = false;
            for (const cand of pathCandidates) {
                const imgRes = await processPastedFilePath(cand, usedImageNames);
                if (imgRes) {
                    foundAnyImage = true;
                    const tag = `[${imgRes.fileName} ${imgRes.sizeStr}]`;
                    imageAttachments.push({
                        tag,
                        fileName: imgRes.fileName,
                        filePath: imgRes.filePath,
                        sizeStr: imgRes.sizeStr
                    });
                    usedImageNames.add(imgRes.fileName);
                    input = input.slice(0, cursorPos) + tag + ' ' + input.slice(cursorPos);
                    cursorPos += tag.length + 1;
                }
            }
            if (foundAnyImage) {
                selectedIndex = 0;
                render();
                return;
            }
            const normalized = cleaned.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
            const lines = normalized.split('\n');
            if (lines.length >= 2 || normalized.length > 80) {
                const linesCount = lines.length;
                const id = pastes.length + 1;
                const tag = `[Pasted text #${id} +${linesCount} lines]`;
                pastes.push({ id, tag, content: normalized, linesCount });
                input = input.slice(0, cursorPos) + tag + input.slice(cursorPos);
                cursorPos += tag.length;
                selectedIndex = 0;
                render();
            }
            else {
                input = input.slice(0, cursorPos) + normalized + input.slice(cursorPos);
                cursorPos += normalized.length;
                selectedIndex = 0;
                render();
            }
        }
        function getTagSpans() {
            const spans = [];
            for (const p of pastes) {
                let pos = 0;
                while ((pos = input.indexOf(p.tag, pos)) !== -1) {
                    spans.push({
                        start: pos,
                        end: pos + p.tag.length,
                        tag: p.tag
                    });
                    pos += p.tag.length;
                }
            }
            for (const img of imageAttachments) {
                let pos = 0;
                while ((pos = input.indexOf(img.tag, pos)) !== -1) {
                    spans.push({
                        start: pos,
                        end: pos + img.tag.length,
                        tag: img.tag
                    });
                    pos += img.tag.length;
                }
            }
            return spans.sort((a, b) => a.start - b.start);
        }
        let pasteBuffer = '';
        let inBracketedPaste = false;
        function onData(chunk) {
            if (disposed)
                return;
            const str = chunk.toString('utf8');
            // Check for bracketed paste start/end
            if (str.includes('\x1b[200~')) {
                inBracketedPaste = true;
                pasteBuffer = '';
            }
            if (inBracketedPaste) {
                pasteBuffer += str;
                if (pasteBuffer.includes('\x1b[201~')) {
                    inBracketedPaste = false;
                    handlePaste(pasteBuffer);
                    pasteBuffer = '';
                }
                return;
            }
            // Check for raw multi-character paste with newlines or large size
            if (str.length > 1 && (str.includes('\n') || str.includes('\r') || str.length > 80)) {
                handlePaste(str);
                return;
            }
            // Handle individual keys
            // Ctrl+V (ASCII 22 / 0x16) or Ctrl+P (ASCII 16 / 0x10)
            if (str === '\x16' || str === '\x10' || (str.length === 1 && (str.charCodeAt(0) === 22 || str.charCodeAt(0) === 16))) {
                saveClipboardImage('image.png', usedImageNames).then((imgRes) => {
                    if (imgRes) {
                        const tag = `[${imgRes.fileName} ${imgRes.sizeStr}]`;
                        imageAttachments.push({
                            tag,
                            fileName: imgRes.fileName,
                            filePath: imgRes.filePath,
                            sizeStr: imgRes.sizeStr
                        });
                        usedImageNames.add(imgRes.fileName);
                        input = input.slice(0, cursorPos) + tag + ' ' + input.slice(cursorPos);
                        cursorPos += tag.length + 1;
                        render();
                    }
                }).catch(() => { });
                return;
            }
            // Ctrl+C
            if (str === '\x03' || (str.length === 1 && str.charCodeAt(0) === 3)) {
                if (lastDropdownLines > 0) {
                    for (let i = 0; i < lastDropdownLines; i++) {
                        process.stdout.write(`\n\x1b[2K`);
                    }
                    process.stdout.write(`\x1b[${lastDropdownLines}A\r`);
                    lastDropdownLines = 0;
                }
                process.stdout.write(`\r\x1b[2K\n`);
                cleanup();
                resolve('__CANCEL__');
                return;
            }
            const items = getDropdownItems();
            // Enter / Return
            if (str === '\r' || str === '\n' || (str.length === 1 && (str.charCodeAt(0) === 13 || str.charCodeAt(0) === 10))) {
                // Direct /image command
                if (input.trim() === '/image' || (items.length > 0 && selectedIndex >= 0 && selectedIndex < items.length && items[selectedIndex].label === '/image')) {
                    saveClipboardImage('image.png', usedImageNames).then((imgRes) => {
                        if (imgRes) {
                            const tag = `[${imgRes.fileName} ${imgRes.sizeStr}]`;
                            imageAttachments.push({
                                tag,
                                fileName: imgRes.fileName,
                                filePath: imgRes.filePath,
                                sizeStr: imgRes.sizeStr
                            });
                            usedImageNames.add(imgRes.fileName);
                            input = `${tag} `;
                            cursorPos = input.length;
                            render();
                        }
                        else {
                            process.stdout.write(`\r\x1b[2K${pc.yellow('⚠️  No image in clipboard. Take a screenshot first (Win+Shift+S)\n')}`);
                            input = '';
                            cursorPos = 0;
                            render();
                        }
                    }).catch(() => { });
                    return;
                }
                if (items.length > 0 && selectedIndex >= 0 && selectedIndex < items.length) {
                    const selected = items[selectedIndex];
                    if (input.startsWith('/')) {
                        input = selected.replacement;
                        clearBoxAndExit(input);
                        return;
                    }
                    else {
                        // @ mention autocomplete on Enter
                        input = input.slice(0, selected.replaceStart) + selected.replacement + input.slice(selected.replaceStart + selected.replaceLen);
                        cursorPos = selected.replaceStart + selected.replacement.length;
                        render();
                        return;
                    }
                }
                clearBoxAndExit(input);
                return;
            }
            // Tab
            if (str === '\t' || (str.length === 1 && str.charCodeAt(0) === 9)) {
                if (items.length > 0 && selectedIndex >= 0 && selectedIndex < items.length) {
                    const selected = items[selectedIndex];
                    input = input.slice(0, selected.replaceStart) + selected.replacement + input.slice(selected.replaceStart + selected.replaceLen);
                    cursorPos = selected.replaceStart + selected.replacement.length;
                    render();
                }
                else {
                    if (lastDropdownLines > 0) {
                        for (let i = 0; i < lastDropdownLines; i++) {
                            process.stdout.write(`\n\x1b[2K`);
                        }
                        process.stdout.write(`\x1b[${lastDropdownLines}A\r`);
                        lastDropdownLines = 0;
                    }
                    process.stdout.write(`\r\x1b[2K`);
                    cleanup();
                    resolve(`__TOGGLE_MODE__:${input}`);
                }
                return;
            }
            // Up Arrow
            if (str === '\x1b[A' || str === '\x1bOA') {
                if (items.length > 0) {
                    selectedIndex = (selectedIndex - 1 + items.length) % items.length;
                    render();
                }
                else if (GLOBAL_PROMPT_HISTORY.length > 0 && historyIndex > 0) {
                    if (historyIndex === GLOBAL_PROMPT_HISTORY.length) {
                        tempDraft = input;
                    }
                    historyIndex--;
                    input = GLOBAL_PROMPT_HISTORY[historyIndex];
                    cursorPos = input.length;
                    render();
                }
                return;
            }
            // Down Arrow
            if (str === '\x1b[B' || str === '\x1bOB') {
                if (items.length > 0) {
                    selectedIndex = (selectedIndex + 1) % items.length;
                    render();
                }
                else if (historyIndex < GLOBAL_PROMPT_HISTORY.length) {
                    historyIndex++;
                    if (historyIndex === GLOBAL_PROMPT_HISTORY.length) {
                        input = tempDraft;
                    }
                    else {
                        input = GLOBAL_PROMPT_HISTORY[historyIndex];
                    }
                    cursorPos = input.length;
                    render();
                }
                return;
            }
            // Left Arrow (Jump across whole paste badge atomically)
            if (str === '\x1b[D' || str === '\x1bOD') {
                if (cursorPos > 0) {
                    const spans = getTagSpans();
                    const endingSpan = spans.find(s => s.end === cursorPos);
                    if (endingSpan) {
                        cursorPos = endingSpan.start;
                    }
                    else {
                        cursorPos--;
                        const inside = spans.find(s => cursorPos > s.start && cursorPos < s.end);
                        if (inside)
                            cursorPos = inside.start;
                    }
                    render();
                }
                return;
            }
            // Right Arrow (Jump across whole paste badge atomically)
            if (str === '\x1b[C' || str === '\x1bOC') {
                if (cursorPos < input.length) {
                    const spans = getTagSpans();
                    const startingSpan = spans.find(s => s.start === cursorPos);
                    if (startingSpan) {
                        cursorPos = startingSpan.end;
                    }
                    else {
                        cursorPos++;
                        const inside = spans.find(s => cursorPos > s.start && cursorPos < s.end);
                        if (inside)
                            cursorPos = inside.end;
                    }
                    render();
                }
                return;
            }
            // Home
            if (str === '\x1b[H' || str === '\x1b[1~') {
                cursorPos = 0;
                render();
                return;
            }
            // End
            if (str === '\x1b[F' || str === '\x1b[4~') {
                cursorPos = input.length;
                render();
                return;
            }
            // Delete key (\x1b[3~)
            if (str === '\x1b[3~') {
                if (cursorPos < input.length) {
                    const spans = getTagSpans();
                    const startingSpan = spans.find(s => s.start === cursorPos);
                    if (startingSpan) {
                        input = input.slice(0, startingSpan.start) + input.slice(startingSpan.end);
                    }
                    else {
                        input = input.slice(0, cursorPos) + input.slice(cursorPos + 1);
                    }
                    selectedIndex = 0;
                    render();
                }
                return;
            }
            // Backspace
            if (str === '\x08' || str === '\x7f' || (str.length === 1 && (str.charCodeAt(0) === 8 || str.charCodeAt(0) === 127))) {
                if (cursorPos > 0) {
                    const spans = getTagSpans();
                    const endingSpan = spans.find(s => s.end === cursorPos);
                    if (endingSpan) {
                        input = input.slice(0, endingSpan.start) + input.slice(endingSpan.end);
                        cursorPos = endingSpan.start;
                    }
                    else {
                        input = input.slice(0, cursorPos - 1) + input.slice(cursorPos);
                        cursorPos--;
                    }
                    selectedIndex = 0;
                    render();
                }
                return;
            }
            // Ignore unknown escape sequences
            if (str.startsWith('\x1b')) {
                return;
            }
            // Normal character input - snap out of span if needed
            const spans = getTagSpans();
            const inside = spans.find(s => cursorPos > s.start && cursorPos < s.end);
            if (inside) {
                cursorPos = inside.end;
            }
            input = input.slice(0, cursorPos) + str + input.slice(cursorPos);
            cursorPos += str.length;
            selectedIndex = 0;
            render();
        }
        process.stdin.on('data', onData);
    });
}
