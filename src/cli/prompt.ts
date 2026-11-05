import type { CostState } from '../core/cost.js';
import pc from 'picocolors';
import { scanProjectFiles } from './files.js';
import { IMAGE_TAG_SOURCE, imageTag, pruneMedia, processPastedFilePath, readClipboardText, saveClipboardImage } from './clipboard.js';
import { getCurrentTheme, listThemes } from './theme.js';
import { CustomCommandManager } from '../core/commands.js';
import { formatPromptHeader } from './modeborder.js';
import { filterCommandPalette, estimatePromptTokens, formatPromptTokenBadge } from './prompt_palette.js';
import { PromptHistoryNavigator } from './history_search.js';
import { buildPromptFrame, palette, renderUserEcho, resolveMode } from './ui.js';
import { padEndWidth, truncateWidth } from './width.js';

import { UIConfig } from '../core/types.js';

export const SLASH_COMMANDS = [
  { cmd: '/new', desc: 'Start a new clean chat session' },
  { cmd: '/resume', desc: 'Resume a previous chat session' },
  { cmd: '/session', desc: 'Show active session ID, stats, and info' },
  { cmd: '/session del', desc: 'Select and delete saved sessions' },
  { cmd: '/usage', desc: 'Show network bandwidth, data saver & token cost' },
  { cmd: '/export', desc: 'Export session conversation to Markdown' },
  { cmd: '/mcp', desc: 'Manage Model Context Protocol (MCP) servers & tools' },
  { cmd: '/theme', desc: 'Switch UI theme (18 palettes: Nord, Dracula, Tokyo Night...)' },
  { cmd: '/doctor', desc: 'Run system & environment health diagnostics' },
  { cmd: '/settings', desc: 'Configure permissions & auto-approval' },
  { cmd: '/update', desc: 'Check and install updates from GitHub' },
  { cmd: '/model', desc: 'Switch model for current provider' },
  { cmd: '/effort', desc: 'Set the reasoning effort for the current model' },
  { cmd: '/provider', desc: 'Switch AI provider (OpenRouter, Google, etc.)' },
  { cmd: '/plan', desc: 'Switch to PLAN mode (architect & planner)' },
  { cmd: '/agent', desc: 'Switch to AGENT mode (coder & executor)' },
  { cmd: '/image', desc: 'Paste the image from the clipboard (same as Ctrl+V or Alt+V)' },
  { cmd: '/serve', desc: 'Start local web server for web/game preview' },
  { cmd: '/memory', desc: 'View, add, or clear project memory bank' },
  { cmd: '/undo', desc: 'Revert last file changes made by AI' },
  { cmd: '/diff', desc: 'Show git diff of modified project files' },
  { cmd: '/commit', desc: 'AI-generated git commit message & commit' },
  { cmd: '/status', desc: 'Show git repository file status' },
  { cmd: '/compact', desc: 'Compact & summarize chat context' },
  { cmd: '/clear', desc: 'Clear context and start a new session' },
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
  /** Shown in the borders of the framed prompt (clean layout). */
  model?: string;
  cwd?: string;
  branch?: string;
  dirty?: boolean;
  contextPercent?: number;
  cost?: number;
  costState?: CostState;
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
    const cleanLayout = opts.ui?.layout !== 'classic';
    const promptPrefix = cleanLayout ? pc.cyan('›') + ' ' : pc.dim('│') + '  ';
    const promptPrefixWidth = cleanLayout ? 2 : 3;
    if (!cleanLayout) {
      if (opts.ui?.modeBorder) {
        const header = formatPromptHeader(!!opts.planMode, !!opts.isYolo, opts.message);
        console.log(header);
      } else {
        const msg = opts.message
          ? `${opts.message} ${pc.dim(`(Tab = ${opts.planMode ? 'AGENT' : 'PLAN'})`)}`
          : `Ask anything... ${pc.dim(`(Tab = ${opts.planMode ? 'AGENT' : 'PLAN'})`)}`;
        console.log(theme.colorFn('◆') + '  ' + pc.bold(msg));
      }
    }

    let availableFiles: string[] = [];
    let customCommandsList: Array<{ cmd: string; desc: string }> = [];

    scanProjectFiles().then((f) => (availableFiles = f)).catch(() => {});
    void pruneMedia().catch(() => {});
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

    const placeholder = opts.placeholder || 'Describe a task, @file, /help, Ctrl+V pastes an image, or press Tab to switch mode';

    let disposed = false;

    process.stdin.resume();
    if (process.stdin.isTTY) {
      process.stdin.setRawMode(true);
      process.stdout.write('\x1b[?2004h'); // Enable bracketed paste mode
    }

    // ── framed prompt (clean layout) ──
    // The frame is a block of rows; the cursor rests on the input row. Redrawing moves up
    // to the top of the block, rewrites every row and returns to the cursor position.
    let frameRowsDrawn = 0;
    let frameCursorRow = 0;

    function cleanDropdownRows(items: DropdownItem[], cols: number, rows: number): string[] {
      if (items.length === 0) return [];
      if (selectedIndex >= items.length) selectedIndex = items.length - 1;
      if (selectedIndex < 0) selectedIndex = 0;

      const pageSize = Math.max(2, Math.min(6, rows - 7));
      let startIndex = 0;
      if (items.length > pageSize && selectedIndex >= pageSize) {
        startIndex = Math.min(selectedIndex - pageSize + 1, items.length - pageSize);
      }
      const endIndex = Math.min(startIndex + pageSize, items.length);
      const labelWidth = Math.min(22, Math.max(8, Math.floor(cols * 0.4)));
      const descRoom = cols - 1 - 2 - 2 - labelWidth - 2;

      const out: string[] = [];
      for (let i = startIndex; i < endIndex; i++) {
        const item = items[i];
        const selected = i === selectedIndex;
        const label = truncateWidth(item.label, labelWidth);
        const labelCell = padEndWidth(selected ? palette.bold(palette.accent(label)) : palette.text(label), labelWidth);
        const desc = descRoom >= 6 ? truncateWidth(item.desc, descRoom) : '';
        out.push(`${selected ? palette.accent('›') : ' '} ${labelCell}${desc ? '  ' + palette.faint(desc) : ''}`);
      }
      return out;
    }

    function drawFrame() {
      const cols = process.stdout.columns || 80;
      const rows = process.stdout.rows || 24;
      const frame = buildPromptFrame({
        cols,
        mode: resolveMode(!!opts.planMode, !!opts.isYolo),
        model: opts.model,
        cwd: opts.cwd,
        branch: opts.branch,
        dirty: opts.dirty,
        contextPercent: opts.contextPercent ?? 0,
        cost: opts.cost ?? 0,
        costState: opts.costState,
        input,
        cursorPos,
        placeholder,
        below: cleanDropdownRows(getDropdownItems(), cols, rows),
      });

      let buf = '\x1b[0m';
      if (frameRowsDrawn > 0 && frameCursorRow > 0) buf += `\x1b[${frameCursorRow}A`;
      buf += '\r';
      frame.lines.forEach((line, i) => {
        buf += '\x1b[2K' + line + '\x1b[0m' + (i < frame.lines.length - 1 ? '\n' : '');
      });
      let rowNow = frame.lines.length - 1;
      for (let k = frame.lines.length; k < frameRowsDrawn; k++) {
        buf += '\n\x1b[2K'; // wipe rows left over from a taller previous frame
        rowNow++;
      }
      if (rowNow > frame.cursorRow) buf += `\x1b[${rowNow - frame.cursorRow}A`;
      buf += '\r' + (frame.cursorCol > 0 ? `\x1b[${frame.cursorCol}C` : '');
      process.stdout.write(buf);
      frameRowsDrawn = frame.lines.length;
      frameCursorRow = frame.cursorRow;
    }

    function eraseFrame() {
      if (frameRowsDrawn === 0) return;
      process.stdout.write(`\x1b[0m${frameCursorRow > 0 ? `\x1b[${frameCursorRow}A` : ''}\r\x1b[J`);
      frameRowsDrawn = 0;
      frameCursorRow = 0;
    }

    const onResize = () => {
      if (disposed) return;
      if (cleanLayout) eraseFrame();
      render();
    };
    process.stdout.on('resize', onResize);

    function getTagSpans(): Array<{ start: number; end: number }> {
      const spans: Array<{ start: number; end: number }> = [];
      const regex = new RegExp(`\\[Pasted text #\\d+ \\+\\d+ lines\\]|${IMAGE_TAG_SOURCE}`, 'g');
      let match;
      while ((match = regex.exec(input)) !== null) {
        spans.push({ start: match.index, end: match.index + match[0].length });
      }
      return spans;
    }

    function formatInputWithBadges(str: string): string {
      return str.replace(
        new RegExp(`(\\[Pasted text #\\d+ \\+\\d+ lines\\]|${IMAGE_TAG_SOURCE})`, 'g'),
        (match) => (cleanLayout ? pc.dim(match) : theme.badgeFn(` ${match.slice(1, -1)} `)) + '\x1b[0m'
      );
    }

    function expandPastes(text: string): string {
      let expanded = text;
      for (const block of pastedBlocks) {
        expanded = expanded.split(block.tag).join(block.text);
      }
      for (const img of imageAttachments) {
        const p = img.filePath.replace(/\\/g, '/');
        expanded = expanded.split(img.tag).join(/^[A-Za-z0-9_\-./\u0400-\u04FF]+$/.test(p) ? `@${p} ` : `@"${p}" `);
      }
      return expanded;
    }

    let imageCounter = 0;
    let pasting = false;

    /** Puts an image into the input as a badge; the file path goes into the message when it is sent. */
    function attachImage(img: { fileName: string; filePath: string; sizeStr: string }) {
      const tag = imageTag(++imageCounter, img.sizeStr);
      imageAttachments.push({ tag, fileName: img.fileName, filePath: img.filePath, sizeStr: img.sizeStr });
      usedImageNames.add(img.fileName);
      input = input.slice(0, cursorPos) + tag + ' ' + input.slice(cursorPos);
      cursorPos += tag.length + 1;
      render();
    }

    /** A one-line message above the prompt (it stays in the scrollback, like other notices). */
    function say(text: string) {
      if (cleanLayout) eraseFrame();
      process.stdout.write(`\x1b[0m\r\x1b[2K${pc.yellow(text)}\n`);
      render();
    }

    /**
     * Ctrl+V, Alt+V and the /image command: the image on the clipboard if there is one,
     * otherwise the text. (A terminal only pastes text by itself; an image needs this.)
     */
    async function pasteFromClipboard() {
      if (pasting) return;
      pasting = true;
      try {
        const img = await saveClipboardImage('image.png', usedImageNames);
        if (disposed) return;
        if (img) {
          attachImage(img);
          return;
        }
        const text = await readClipboardText();
        if (disposed) return;
        if (text) {
          handlePaste(text);
          return;
        }
        const termux = !!process.env.PREFIX?.includes('com.termux');
        say(
          termux
            ? 'Nothing to paste. On Termux an image cannot be read from the clipboard: give the file path instead (@image.png).'
            : 'Nothing to paste: the clipboard has no image or text. (Wayland needs wl-clipboard, X11 needs xclip.)'
        );
      } finally {
        pasting = false;
      }
    }

    function handlePaste(rawText: string) {
      // A short single-line chunk often arrives together with its Enter (fast typing, IME):
      // a stray CR / LF must never end up inside the input line.
      const text = rawText.replace(/(?:\r\n|\r|\n)+$/, '');
      const trimmed = text.trim();

      // Some terminals send an empty paste when the clipboard holds only an image.
      if (!trimmed) {
        void pasteFromClipboard();
        return;
      }

      const lines = trimmed.split(/\r\n|\r|\n/);

      // A path to an image (dragged into the terminal or copied from a file manager).
      if (lines.length === 1 && /\.(png|jpe?g|webp|gif)['"]?$/i.test(trimmed)) {
        processPastedFilePath(trimmed, usedImageNames)
          .then((imgRes) => {
            if (disposed) return;
            if (imgRes) attachImage(imgRes);
            else insertText(text);
          })
          .catch(() => {});
        return;
      }

      if (lines.length <= 1 && text.length < 80) {
        insertText(text);
        return;
      }

      const id = nextPasteId++;
      const tag = `[Pasted text #${id} +${lines.length} lines]`;
      pastedBlocks.push({ id, lines: lines.length, text, tag });

      input = input.slice(0, cursorPos) + tag + input.slice(cursorPos);
      cursorPos += tag.length;
      render();
    }

    function insertText(text: string) {
      input = input.slice(0, cursorPos) + text + input.slice(cursorPos);
      cursorPos += text.length;
      render();
    }

    function getDropdownItems(): DropdownItem[] {
      if (input.startsWith('/')) {
        const q = input.trim().toLowerCase();
        if (cleanLayout || opts.ui?.commandPalette) {
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
      if (cleanLayout) {
        drawFrame();
        return;
      }

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

        if (cleanLayout) {
          for (let i = startIndex; i < endIndex; i++) {
            const item = items[i];
            const selected = i === selectedIndex;
            const labelWidth = Math.min(20, cols - 5);
            const label = item.label.length > labelWidth ? `${item.label.slice(0, labelWidth - 1)}…` : item.label;
            const descWidth = cols - label.length - 7;
            const desc = descWidth >= 5
              ? item.desc.slice(0, descWidth)
              : '';
            dropdownLines.push(`  ${selected ? pc.cyan('›') : ' '} ${selected ? pc.bold(label) : label}${desc ? `  ${pc.dim(desc)}` : ''}`);
          }
        } else {
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
      }

      // 1. Format and write the Prompt line safely
      let inputDisplay = promptPrefix;
      let renderCursorCol = promptPrefixWidth;

      if (input.length === 0) {
        const maxPlace = Math.max(10, cols - promptPrefixWidth - 2);
        const displayPlace =
          placeholder.length > maxPlace ? placeholder.slice(0, maxPlace - 1) + '…' : placeholder;
        inputDisplay += pc.dim(displayPlace) + '\x1b[0m';
        renderCursorCol = promptPrefixWidth;
      } else {
        const tokenBadge = !cleanLayout && opts.ui?.inputTokenCounter ? formatPromptTokenBadge(estimatePromptTokens(input), 40) : '';
        const badgeSuffix = tokenBadge ? ` ${tokenBadge}` : '';
        const maxInputLen = Math.max(10, cols - promptPrefixWidth - 3 - (tokenBadge ? 12 : 0));
        if (input.length <= maxInputLen) {
          inputDisplay += formatInputWithBadges(input) + badgeSuffix + '\x1b[0m';
          renderCursorCol = promptPrefixWidth + cursorPos;
        } else {
          // Horizontal scrolling to prevent auto-wrapping
          const start = Math.max(0, Math.min(cursorPos - Math.floor(maxInputLen / 2), input.length - maxInputLen));
          const visibleChunk = input.slice(start, start + maxInputLen);
          const prefix = start > 0 ? '…' : '';
          const suffix = start + maxInputLen < input.length ? '…' : '';
          inputDisplay += pc.dim(prefix) + formatInputWithBadges(visibleChunk) + pc.dim(suffix) + badgeSuffix + '\x1b[0m';
          renderCursorCol = promptPrefixWidth + (prefix ? 1 : 0) + (cursorPos - start);
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
      if (cleanLayout) {
        eraseFrame();
        const fullTextClean = expandPastes(finalInput);
        if (fullTextClean.trim()) {
          if (
            GLOBAL_PROMPT_HISTORY.length === 0 ||
            GLOBAL_PROMPT_HISTORY[GLOBAL_PROMPT_HISTORY.length - 1] !== fullTextClean.trim()
          ) {
            GLOBAL_PROMPT_HISTORY.push(fullTextClean.trim());
          }
        }
        process.stdout.write(renderUserEcho(finalInput, process.stdout.columns || 80).join('\n') + '\n');
        cleanup();
        resolve(fullTextClean);
        return;
      }
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

      process.stdout.write(`\x1b[0m\r\x1b[2K${promptPrefix}${formatInputWithBadges(finalInput)}\x1b[0m\n\n`);
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

      // Ctrl+V and Alt+V (also as the sequences of terminals with an enhanced keyboard mode).
      if (str === '\x16' || str === '\x1bv' || str === '\x1bV' || str === '\x1b[118;5u' || str === '\x1b[118;3u') {
        void pasteFromClipboard();
        return;
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
        if (cleanLayout) {
          eraseFrame();
          cleanup();
          resolve('__CANCEL__');
          return;
        }
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
          input = '';
          cursorPos = 0;
          void pasteFromClipboard();
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
          if (cleanLayout) eraseFrame();
          else process.stdout.write(`\x1b[0m\r\x1b[2K`);
          cleanup();
          resolve(`__TOGGLE_MODE__:${input}`);
        }
        return;
      }

      // Up Arrow
      if (str === '\x1b[A' || str === '\x1bOA') {
        if (items.length > 0) {
          selectedIndex = Math.max(0, selectedIndex - 1); // stops at the first item
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
          selectedIndex = Math.min(items.length - 1, selectedIndex + 1); // stops at the last item
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
