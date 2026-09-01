import pc from 'picocolors';
import { scanProjectFiles } from './files.js';
import { saveClipboardImage, processPastedFilePath } from './clipboard.js';
import { getCurrentTheme, listThemes } from './theme.js';
import { CustomCommandManager } from '../core/commands.js';
import { formatPromptHeader } from './modeborder.js';
import { filterCommandPalette, estimatePromptTokens, formatPromptTokenBadge } from './prompt_palette.js';
import { PromptHistoryNavigator } from './history_search.js';

import { UIConfig } from '../core/types.js';

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

const GLOBAL_PROMPT_HISTORY: string[] = [];

export interface AskPromptOptions {
  message?: string;
  placeholder?: string;
  initialValue?: string;
  planMode?: boolean;
  isYolo?: boolean;
  ui?: UIConfig;
}

interface DropdownItem {
  label: string;
  desc: string;
  replacement: string;
  replaceStart: number;
  replaceLen: number;
}

interface PastedBlock {
  id: number;
  lines: number;
  text: string;
  tag: string;
}

function stripAnsi(str: string): string {
  return str.replace(/\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])/g, '');
}

export function askPrompt(opts: AskPromptOptions = {}): Promise<string> {
  return new Promise((resolve) => {
    const theme = getCurrentTheme();
    if (opts.ui?.modeBorder) {
      const header = formatPromptHeader(!!opts.planMode, !!opts.isYolo, opts.message);
      console.log(header);
    } else {
      const msg = opts.message
        ? `${opts.message} ${pc.dim(`(Tab = ${opts.planMode ? 'AGENT' : 'PLAN'})`)}`
        : `Ask anything... ${pc.dim(`(Tab = ${opts.planMode ? 'AGENT' : 'PLAN'})`)}`;
      console.log(theme.colorFn('◆') + '  ' + pc.bold(msg));
    }

    let availableFiles: string[] = [];
    let customCommandsList: Array<{ cmd: string; desc: string }> = [];

    scanProjectFiles().then((f) => (availableFiles = f)).catch(() => {});
    CustomCommandManager.listCommands()
      .then((c) => (customCommandsList = c.map((x) => ({ cmd: x.cmd, desc: x.desc }))))
      .catch(() => {});

    let input = opts.initialValue || '';
    let cursorPos = input.length;
    let selectedIndex = 0;
    let lastRenderedDropdownLines = 0;

    const historyNav = new PromptHistoryNavigator(GLOBAL_PROMPT_HISTORY, !!opts.ui?.promptHistoryFuzzy);
    PromptHistoryNavigator.loadPersistent().then((persisted) => {
      for (const p of persisted) {
        if (!GLOBAL_PROMPT_HISTORY.includes(p)) {
          GLOBAL_PROMPT_HISTORY.push(p);
        }
      }
    }).catch(() => {});

    const imageAttachments: Array<{ tag: string; fileName: string; filePath: string; sizeStr: string }> = [];
    const usedImageNames = new Set<string>();

    const pastedBlocks: PastedBlock[] = [];
    let nextPasteId = 1;

    let inBracketedPaste = false;
    let bracketedBuffer = '';

    const placeholder = opts.placeholder || 'Describe a task, @file, /help, paste image, or press Tab to switch mode';

    let disposed = false;

    process.stdin.resume();
    if (process.stdin.isTTY) {
      process.stdin.setRawMode(true);
      process.stdout.write('\x1b[?2004h'); // Enable bracketed paste mode
    }

    const onResize = () => { if (!disposed) render(); };
    process.stdout.on('resize', onResize);

    function getTagSpans(): Array<{ start: number; end: number }> {
      const spans: Array<{ start: number; end: number }> = [];
      const regex = /\[(?:Pasted text #\d+ \+\d+ lines|\d+\.png \d+kb)\]/g;
      let match;
      while ((match = regex.exec(input)) !== null) {
        spans.push({ start: match.index, end: match.index + match[0].length });
      }
      return spans;
    }

    function formatInputWithBadges(str: string): string {
      return str.replace(
        /(\[(?:Pasted text #\d+ \+\d+ lines|\d+\.png \d+kb)\])/g,
        (match) => theme.badgeFn(` ${match.slice(1, -1)} `) + '\x1b[0m'
      );
    }

    function expandPastes(text: string): string {
      let expanded = text;
      for (const block of pastedBlocks) {
        expanded = expanded.split(block.tag).join(block.text);
      }
      for (const img of imageAttachments) {
        expanded = expanded.split(img.tag).join(`@${img.filePath.replace(/\\/g, '/')} `);
      }
      return expanded;
    }

    function handlePaste(text: string) {
      const trimmed = text.trim();
      const lines = trimmed.split(/\r\n|\r|\n/);
      if (lines.length <= 1 && text.length < 80) {
        input = input.slice(0, cursorPos) + text + input.slice(cursorPos);
        cursorPos += text.length;
        render();
        return;
      }

      if (trimmed.match(/\.(png|jpe?g|webp|gif|bmp)$/i)) {
        processPastedFilePath(trimmed, usedImageNames)
          .then((imgRes) => {
            if (imgRes && !disposed) {
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
          })
          .catch(() => {});
        return;
      }

      const id = nextPasteId++;
      const tag = `[Pasted text #${id} +${lines.length} lines]`;
      pastedBlocks.push({ id, lines: lines.length, text, tag });

      input = input.slice(0, cursorPos) + tag + input.slice(cursorPos);
      cursorPos += tag.length;
      render();
    }

    function getDropdownItems(): DropdownItem[] {
      if (input.startsWith('/')) {
        const q = input.trim().toLowerCase();
        if (opts.ui?.commandPalette) {
          const baseList = [
            ...SLASH_COMMANDS.map((c) => ({ cmd: c.cmd, desc: c.desc })),
            ...customCommandsList.map((c) => ({ cmd: c.cmd, desc: `(custom) ${c.desc}` }))
          ];

          const filtered = filterCommandPalette(input, baseList).slice(0, 15);
          return filtered.map((item) => ({
            label: item.cmd,
            desc: item.desc,
            replacement: `${item.cmd} `,
            replaceStart: 0,
            replaceLen: input.length
          }));
        } else {
          const baseList = [
            ...SLASH_COMMANDS.map((c) => ({ label: c.cmd, desc: c.desc, replacement: c.cmd })),
            ...customCommandsList.map((c) => ({ label: c.cmd, desc: `(custom) ${c.desc}`, replacement: c.cmd }))
          ];

          const filtered = baseList.filter((c) => c.label.toLowerCase().startsWith(q) || q === '/');
          return filtered.map((item) => ({
            label: item.label,
            desc: item.desc,
            replacement: item.replacement,
            replaceStart: 0,
            replaceLen: input.length
          }));
        }
      }

      const beforeCursor = input.slice(0, cursorPos);
      const atMatch = beforeCursor.match(/@([a-zA-Z0-9_\-./\u0400-\u04FF]*)$/);
      if (atMatch && availableFiles.length > 0) {
        const query = atMatch[1].toLowerCase();
        const replaceStart = cursorPos - atMatch[0].length;
        const replaceLen = atMatch[0].length;

        const filtered = availableFiles
          .filter((f) => f.toLowerCase().includes(query) || query === '')
          .slice(0, 15);

        return filtered.map((file) => ({
          label: `@${file}`,
          desc: file.endsWith('/') ? 'Directory' : 'File',
          replacement: `@${file}${file.endsWith('/') ? '' : ' '}`,
          replaceStart,
          replaceLen
        }));
      }

      return [];
    }

    function render() {
      if (disposed) return;

      const cols = Math.max(20, process.stdout.columns || 40);
      const rows = Math.max(8, process.stdout.rows || 20);

      const items = getDropdownItems();
      const dropdownLines: string[] = [];

      if (items.length > 0) {
        if (selectedIndex >= items.length) selectedIndex = items.length - 1;
        if (selectedIndex < 0) selectedIndex = 0;

        // Limit dropdown height gracefully so it always fits comfortably
        const maxDropdownRows = Math.max(2, Math.min(rows - 3, 5));
        const pageSize = Math.min(4, Math.max(2, Math.min(maxDropdownRows - 2, Math.floor(rows / 4))));
        
        // Strict boundary: left margin (2) + box borders (2) + inner width <= cols - 4
        // This mathematically prevents terminal auto-wrapping on narrow mobile screens
        const boxWidth = Math.max(12, Math.min(cols - 6, 46));
        const innerWidth = Math.max(8, boxWidth - 2);
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

        let topBorderStr = '─'.repeat(innerWidth);
        if (hasMoreUp) {
          const mid = Math.max(0, Math.floor(innerWidth / 2) - 2);
          topBorderStr = '─'.repeat(mid) + ' ▲ ' + '─'.repeat(Math.max(0, innerWidth - mid - 3));
        }

        let botBorderStr = '─'.repeat(innerWidth);
        if (hasMoreDown) {
          const mid = Math.max(0, Math.floor(innerWidth / 2) - 2);
          botBorderStr = '─'.repeat(mid) + ' ▼ ' + '─'.repeat(Math.max(0, innerWidth - mid - 3));
        }

        dropdownLines.push(pc.dim('│ ') + pc.dim('╭' + topBorderStr + '╮') + '\x1b[0m');

        for (let i = startIndex; i < endIndex; i++) {
          const item = items[i];
          const isSelected = i === selectedIndex;
          const pointer = isSelected ? '› ' : '  ';
          const avail = Math.max(4, innerWidth - pointer.length);

          let rowText = '';
          if (avail < 16) {
            const labelStr =
              item.label.length > avail
                ? item.label.slice(0, avail - 1) + '…'
                : item.label.padEnd(avail, ' ');
            rowText = pointer + (isSelected ? theme.boldFn(labelStr) : labelStr);
          } else {
            const labelMax = Math.min(12, Math.max(6, Math.floor(avail * 0.38)));
            const labelStr =
              item.label.length > labelMax
                ? item.label.slice(0, labelMax - 1) + '…'
                : item.label.padEnd(labelMax, ' ');
            const descMax = Math.max(3, avail - labelMax - 1);
            const descStr =
              item.desc.length > descMax
                ? item.desc.slice(0, descMax - 1) + '…'
                : item.desc.padEnd(descMax, ' ');
            rowText = pointer + (isSelected ? theme.boldFn(labelStr) : labelStr) + ' ' + pc.gray(descStr);
          }

          // Calculate visible plain length without ANSI
          const plainLen = rowText.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '').length;
          const padCount = Math.max(0, innerWidth - plainLen);
          const fullRow = rowText + ' '.repeat(padCount);

          if (isSelected) {
            const cleanFull = fullRow.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '');
            dropdownLines.push(pc.dim('│ ') + pc.dim('│') + theme.badgeFn(cleanFull) + pc.dim('│') + '\x1b[0m');
          } else {
            dropdownLines.push(pc.dim('│ ') + pc.dim('│') + fullRow + pc.dim('│') + '\x1b[0m');
          }
        }

        dropdownLines.push(pc.dim('│ ') + pc.dim('╰' + botBorderStr + '╯') + '\x1b[0m');
      }

      // 1. Format and write the Prompt line safely
      let inputDisplay = pc.dim('│') + '  ';
      let renderCursorCol = 3;

      if (input.length === 0) {
        const maxPlace = Math.max(10, cols - 6);
        const displayPlace =
          placeholder.length > maxPlace ? placeholder.slice(0, maxPlace - 1) + '…' : placeholder;
        inputDisplay += pc.dim(displayPlace) + '\x1b[0m';
        renderCursorCol = 3;
      } else {
        const tokenBadge = opts.ui?.inputTokenCounter ? formatPromptTokenBadge(estimatePromptTokens(input), 40) : '';
        const badgeSuffix = tokenBadge ? ` ${tokenBadge}` : '';
        const maxInputLen = Math.max(10, cols - 6 - (tokenBadge ? 12 : 0));
        if (input.length <= maxInputLen) {
          inputDisplay += formatInputWithBadges(input) + badgeSuffix + '\x1b[0m';
          renderCursorCol = 3 + cursorPos;
        } else {
          // Horizontal scrolling to prevent auto-wrapping
          const start = Math.max(0, Math.min(cursorPos - Math.floor(maxInputLen / 2), input.length - maxInputLen));
          const visibleChunk = input.slice(start, start + maxInputLen);
          const prefix = start > 0 ? '…' : '';
          const suffix = start + maxInputLen < input.length ? '…' : '';
          inputDisplay += pc.dim(prefix) + formatInputWithBadges(visibleChunk) + pc.dim(suffix) + badgeSuffix + '\x1b[0m';
          renderCursorCol = 3 + (prefix ? 1 : 0) + (cursorPos - start);
        }
      }

      // Write prompt line
      process.stdout.write(`\x1b[0m\r\x1b[2K${inputDisplay}\x1b[0m`);

      // 2. Draw or update dropdown lines safely
      if (dropdownLines.length > lastRenderedDropdownLines) {
        // Allocate additional new lines that don't exist yet
        for (let i = 0; i < lastRenderedDropdownLines; i++) {
          process.stdout.write(`\x1b[1B\r\x1b[0m\x1b[2K${dropdownLines[i]}\x1b[0m`);
        }
        for (let i = lastRenderedDropdownLines; i < dropdownLines.length; i++) {
          process.stdout.write(`\n\x1b[0m\x1b[2K${dropdownLines[i]}\x1b[0m`);
        }
        process.stdout.write(`\x1b[${dropdownLines.length}A`);
        lastRenderedDropdownLines = dropdownLines.length;
      } else if (lastRenderedDropdownLines > 0) {
        // Redraw over existing lines without allocating new ones (0 scrolling!)
        for (let i = 0; i < lastRenderedDropdownLines; i++) {
          process.stdout.write(`\x1b[1B\r\x1b[0m\x1b[2K`);
          if (i < dropdownLines.length) {
            process.stdout.write(`${dropdownLines[i]}\x1b[0m`);
          }
        }
        process.stdout.write(`\x1b[${lastRenderedDropdownLines}A`);
        lastRenderedDropdownLines = dropdownLines.length;
      }

      // 3. Position cursor on the prompt line
      process.stdout.write(`\r\x1b[${renderCursorCol}C`);
    }

    render();

    function cleanup() {
      disposed = true;
      if (process.stdout.isTTY) {
        process.stdout.write('\x1b[?2004l');
      }
      process.stdout.removeListener('resize', onResize);
      process.stdin.removeListener('data', onData);
      if (process.stdin.isTTY) {
        process.stdin.setRawMode(false);
      }
      process.stdin.pause();
    }

    function clearBoxAndExit(finalInput: string) {
      if (lastRenderedDropdownLines > 0) {
        for (let i = 0; i < lastRenderedDropdownLines; i++) {
          process.stdout.write(`\x1b[1B\r\x1b[0m\x1b[2K`);
        }
        process.stdout.write(`\x1b[${lastRenderedDropdownLines}A`);
        lastRenderedDropdownLines = 0;
      }

      const fullText = expandPastes(finalInput);
      if (fullText.trim()) {
        if (
          GLOBAL_PROMPT_HISTORY.length === 0 ||
          GLOBAL_PROMPT_HISTORY[GLOBAL_PROMPT_HISTORY.length - 1] !== fullText.trim()
        ) {
          GLOBAL_PROMPT_HISTORY.push(fullText.trim());
        }
      }

      process.stdout.write(`\x1b[0m\r\x1b[2K${pc.dim('│')}  ${formatInputWithBadges(finalInput)}\x1b[0m\n\n`);
      cleanup();
      resolve(fullText);
    }

    function onData(data: Buffer) {
      if (disposed) return;
      const str = data.toString('utf-8');

      // Bracketed paste mode handling
      if (str.includes('\x1b[200~')) {
        inBracketedPaste = true;
        bracketedBuffer = '';
        const afterTag = str.split('\x1b[200~')[1] || '';
        if (afterTag.includes('\x1b[201~')) {
          const content = afterTag.split('\x1b[201~')[0];
          inBracketedPaste = false;
          handlePaste(content);
          return;
        } else {
          bracketedBuffer += afterTag;
          return;
        }
      }

      if (inBracketedPaste) {
        if (str.includes('\x1b[201~')) {
          const beforeTag = str.split('\x1b[201~')[0];
          bracketedBuffer += beforeTag;
          inBracketedPaste = false;
          handlePaste(bracketedBuffer);
          return;
        } else {
          bracketedBuffer += str;
          return;
        }
      }

      // Check for raw multi-character paste with newlines or large size
      if (str.length > 1 && (str.includes('\n') || str.includes('\r') || str.length > 80)) {
        handlePaste(str);
        return;
      }

      // Ignore EOF / Ctrl+D (\x04)
      if (str === '\x04') {
        return;
      }

      // Ctrl+C / SIGINT
      if (str === '\x03') {
        if (lastRenderedDropdownLines > 0) {
          for (let i = 0; i < lastRenderedDropdownLines; i++) {
            process.stdout.write(`\x1b[1B\r\x1b[0m\x1b[2K`);
          }
          process.stdout.write(`\x1b[${lastRenderedDropdownLines}A`);
          lastRenderedDropdownLines = 0;
        }
        process.stdout.write(`\x1b[0m\r\x1b[2K\n`);
        cleanup();
        resolve('__CANCEL__');
        return;
      }

      const items = getDropdownItems();

      // Enter / Return
      if (
        str === '\r' ||
        str === '\n' ||
        (str.length === 1 && (str.charCodeAt(0) === 13 || str.charCodeAt(0) === 10))
      ) {
        // Direct /image command
        if (
          input.trim() === '/image' ||
          (items.length > 0 &&
            selectedIndex >= 0 &&
            selectedIndex < items.length &&
            items[selectedIndex].label === '/image')
        ) {
          saveClipboardImage('image.png', usedImageNames)
            .then((imgRes) => {
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
              } else {
                process.stdout.write(
                  `\x1b[0m\r\x1b[2K${pc.yellow('⚠️  No image in clipboard. Take a screenshot first (Win+Shift+S)\n')}`
                );
                input = '';
                cursorPos = 0;
                render();
              }
            })
            .catch(() => {});
          return;
        }

        if (items.length > 0 && selectedIndex >= 0 && selectedIndex < items.length) {
          const selected = items[selectedIndex];
          if (input.startsWith('/')) {
            input = selected.replacement;
            clearBoxAndExit(input);
            return;
          } else {
            // @ mention autocomplete on Enter
            input =
              input.slice(0, selected.replaceStart) +
              selected.replacement +
              input.slice(selected.replaceStart + selected.replaceLen);
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
          if (input.startsWith('/')) {
            input = selected.replacement + ' ';
            cursorPos = input.length;
            render();
            return;
          } else {
            input =
              input.slice(0, selected.replaceStart) +
              selected.replacement +
              input.slice(selected.replaceStart + selected.replaceLen);
            cursorPos = selected.replaceStart + selected.replacement.length;
            render();
            return;
          }
        } else {
          process.stdout.write(`\x1b[0m\r\x1b[2K`);
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
        } else {
          const prev = historyNav.navigateUp(input);
          if (prev !== null) {
            input = prev;
            cursorPos = input.length;
            render();
          }
        }
        return;
      }

      // Down Arrow
      if (str === '\x1b[B' || str === '\x1bOB') {
        if (items.length > 0) {
          selectedIndex = (selectedIndex + 1) % items.length;
          render();
        } else {
          const next = historyNav.navigateDown();
          if (next !== null) {
            input = next;
            cursorPos = input.length;
            render();
          }
        }
        return;
      }

      // Left Arrow
      if (str === '\x1b[D' || str === '\x1bOD') {
        if (cursorPos > 0) {
          const spans = getTagSpans();
          const endingSpan = spans.find((s) => s.end === cursorPos);
          if (endingSpan) {
            cursorPos = endingSpan.start;
          } else {
            cursorPos--;
            const inside = spans.find((s) => cursorPos > s.start && cursorPos < s.end);
            if (inside) cursorPos = inside.start;
          }
          render();
        }
        return;
      }

      // Right Arrow
      if (str === '\x1b[C' || str === '\x1bOC') {
        if (cursorPos < input.length) {
          const spans = getTagSpans();
          const startingSpan = spans.find((s) => s.start === cursorPos);
          if (startingSpan) {
            cursorPos = startingSpan.end;
          } else {
            cursorPos++;
            const inside = spans.find((s) => cursorPos > s.start && cursorPos < s.end);
            if (inside) cursorPos = inside.end;
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

      // Delete key
      if (str === '\x1b[3~') {
        if (cursorPos < input.length) {
          const spans = getTagSpans();
          const startingSpan = spans.find((s) => s.start === cursorPos);
          if (startingSpan) {
            input = input.slice(0, startingSpan.start) + input.slice(startingSpan.end);
          } else {
            input = input.slice(0, cursorPos) + input.slice(cursorPos + 1);
          }
          selectedIndex = 0;
          render();
        }
        return;
      }

      // Backspace / DEL handling
      if (str.includes('\x08') || str.includes('\x7f')) {
        for (let i = 0; i < str.length; i++) {
          const ch = str[i];
          if (ch === '\x08' || ch === '\x7f') {
            if (cursorPos > 0) {
              const spans = getTagSpans();
              const endingSpan = spans.find((s) => s.end === cursorPos);
              if (endingSpan) {
                input = input.slice(0, endingSpan.start) + input.slice(endingSpan.end);
                cursorPos = endingSpan.start;
              } else {
                // Handle surrogate pairs (emoji)
                const prevChar = input.charCodeAt(cursorPos - 1);
                const deleteCount = (prevChar >= 0xDC00 && prevChar <= 0xDFFF && cursorPos >= 2) ? 2 : 1;
                input = input.slice(0, cursorPos - deleteCount) + input.slice(cursorPos);
                cursorPos -= deleteCount;
              }
            }
          } else if (ch.charCodeAt(0) >= 32) {
            const spans = getTagSpans();
            const inside = spans.find((s) => cursorPos > s.start && cursorPos < s.end);
            if (inside) cursorPos = inside.end;
            input = input.slice(0, cursorPos) + ch + input.slice(cursorPos);
            cursorPos++;
          }
        }
        selectedIndex = 0;
        render();
        return;
      }

      // Ignore unknown escape sequences
      if (str.startsWith('\x1b')) {
        return;
      }

      // Normal character input - clean control characters
      const cleanStr = str.replace(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g, '');
      if (!cleanStr) return;

      const spans = getTagSpans();
      const inside = spans.find((s) => cursorPos > s.start && cursorPos < s.end);
      if (inside) {
        cursorPos = inside.end;
      }

      input = input.slice(0, cursorPos) + cleanStr + input.slice(cursorPos);
      cursorPos += cleanStr.length;
      selectedIndex = 0;
      render();
    }

    process.stdin.on('data', onData);
  });
}
