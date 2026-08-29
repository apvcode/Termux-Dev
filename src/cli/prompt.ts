import readline from 'readline';
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

const GLOBAL_PROMPT_HISTORY: string[] = [];

export interface AskPromptOptions {
  message?: string;
  placeholder?: string;
  initialValue?: string;
  planMode?: boolean;
}

export function askPrompt(opts: AskPromptOptions = {}): Promise<string> {
  return new Promise((resolve) => {
    process.stdin.resume();
    const msg = opts.message ? `${opts.message} ${pc.dim(`(Tab = ${opts.planMode ? 'AGENT' : 'PLAN'})`)}` : `Ask anything... ${pc.dim(`(Tab = ${opts.planMode ? 'AGENT' : 'PLAN'})`)}`;
    const placeholder = opts.placeholder || 'Describe a task, @file, /help, paste image, or press Tab to switch mode';

    let input = opts.initialValue || '';
    let cursorPos = input.length;
    let selectedIndex = 0;
    let lastDropdownLines = 0;
    let lastCursorLine = 0;
    let disposed = false;
    let historyIndex = GLOBAL_PROMPT_HISTORY.length;
    let tempDraft = '';
    let availableFiles: string[] = [];
    let customCommandsList: Array<{ cmd: string; desc: string }> = [];

    // Preload project files and custom slash commands
    scanProjectFiles().then(files => {
      availableFiles = files;
    }).catch(() => {});

    CustomCommandManager.listCommands().then(cmds => {
      customCommandsList = cmds.map(c => ({ cmd: c.cmd, desc: c.desc }));
    }).catch(() => {});

    const pastes: { id: number; tag: string; content: string; linesCount: number }[] = [];
    const imageAttachments: Array<{ tag: string; fileName: string; filePath: string; sizeStr: string }> = [];
    const usedImageNames = new Set<string>();

    const theme = getCurrentTheme();
    // Header printed once
    console.log(theme.colorFn('◆') + '  ' + pc.bold(msg));

    if (process.stdin.isTTY) {
      process.stdin.setRawMode(true);
    }
    if (process.stdout.isTTY) {
      process.stdout.write('\x1b[?2004h');
    }

    interface DropdownItem {
      label: string;
      desc: string;
      replacement: string;
      replaceStart: number;
      replaceLen: number;
    }

    function getDropdownItems(): DropdownItem[] {
      if (input.startsWith('/theme ') || input.startsWith('/themes ') || input === '/theme') {
        const afterCmd = input.replace(/^\/(?:theme|themes)\s*/i, '').trim().toLowerCase();
        const themes = listThemes();
        const matched = themes.filter(t => 
          !afterCmd || 
          t.id.toLowerCase().startsWith(afterCmd) || 
          t.name.toLowerCase().includes(afterCmd)
        );

        const list: DropdownItem[] = [];
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
        const baseList: { cmd: string; desc: string }[] = [...SLASH_COMMANDS];
        
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

    function formatInputWithBadges(rawText: string): string {
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

    function expandPastes(rawText: string): string {
      let result = rawText;
      for (const p of pastes) {
        result = result.split(p.tag).join(p.content);
      }
      for (const img of imageAttachments) {
        result = result.split(img.tag).join(`@${img.filePath.replace(/\\/g, '/')} `);
      }
      return result;
    }

function stripAnsi(str: string): string {
  return str.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '');
}

    function render() {
      if (disposed) return;

      const items = getDropdownItems();
      const dropdownLines: string[] = [];

      const cols = Math.max(28, process.stdout.columns || 80);
      const rows = Math.max(8, process.stdout.rows || 24);

      if (items.length > 0) {
        if (selectedIndex >= items.length) selectedIndex = 0;
        if (selectedIndex < 0) selectedIndex = items.length - 1;

        // Ensure inner box width strictly fits: innerBoxWidth + 5 <= cols - 2
        const innerBoxWidth = Math.max(16, Math.min(cols - 8, 54));
        const isNarrowOrShort = rows <= 16 || cols < 50;
        const pageSize = isNarrowOrShort
          ? Math.max(2, Math.min(3, rows - 5))
          : Math.min(5, Math.max(3, Math.floor((rows - 4) / 2)));
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

        let topBorderStr = '─'.repeat(innerBoxWidth);
        if (hasMoreUp) {
          const mid = Math.max(0, Math.floor(innerBoxWidth / 2) - 2);
          topBorderStr = '─'.repeat(mid) + ' ▲ ' + '─'.repeat(Math.max(0, innerBoxWidth - mid - 3));
        }

        let botBorderStr = '─'.repeat(innerBoxWidth);
        if (hasMoreDown) {
          const mid = Math.max(0, Math.floor(innerBoxWidth / 2) - 2);
          botBorderStr = '─'.repeat(mid) + ' ▼ ' + '─'.repeat(Math.max(0, innerBoxWidth - mid - 3));
        }

        dropdownLines.push(pc.dim('│') + '  ' + pc.dim('╭' + topBorderStr + '╮'));

        for (let i = startIndex; i < endIndex; i++) {
          const item = items[i];
          const isSelected = i === selectedIndex;
          const pointer = isSelected ? '› ' : '  ';
          const availWidth = innerBoxWidth - 2;

          let row = '';
          if (availWidth < 20) {
            // Very narrow mobile screen: show command name only
            const labelStr = item.label.length > availWidth ? item.label.slice(0, availWidth - 1) + '…' : item.label.padEnd(availWidth);
            const plain = pointer + labelStr;
            row = isSelected ? theme.badgeFn(plain) : (pointer + theme.boldFn(labelStr));
          } else {
            // Show command name and truncated description
            const labelMax = Math.min(15, Math.floor(availWidth * 0.45));
            const labelStr = item.label.length > labelMax ? item.label.slice(0, labelMax - 1) + '…' : item.label.padEnd(labelMax);
            const descMax = availWidth - labelMax - 1;
            const descStr = item.desc.length > descMax ? item.desc.slice(0, descMax - 1) + '…' : item.desc.padEnd(descMax);
            const plain = `${pointer}${labelStr} ${descStr}`;
            
            if (isSelected) {
              row = theme.badgeFn(plain);
            } else {
              row = `${pointer}${theme.boldFn(labelStr)} ${pc.gray(descStr)}`;
            }
          }

          // Safety guarantee: exact visible width must match innerBoxWidth
          const currentLen = stripAnsi(row).length;
          if (currentLen < innerBoxWidth) {
            row += ' '.repeat(innerBoxWidth - currentLen);
          }

          dropdownLines.push(pc.dim('│') + '  ' + pc.dim('│') + row + pc.dim('│'));
        }

        dropdownLines.push(pc.dim('│') + '  ' + pc.dim('╰' + botBorderStr + '╯'));
      }

      // 1. Move cursor back to Line 0 from lastCursorLine and erase everything down
      if (lastCursorLine > 0) {
        process.stdout.write(`\x1b[${lastCursorLine}A`);
      }
      process.stdout.write('\r\x1b[J');

      // 2. Prepare and write input display
      let inputDisplay = pc.dim('│') + '  ';
      if (input.length === 0) {
        const maxPlace = Math.max(12, cols - 8);
        const displayPlace = placeholder.length > maxPlace ? placeholder.slice(0, maxPlace - 1) + '…' : placeholder;
        inputDisplay += pc.dim(displayPlace);
      } else {
        inputDisplay += formatInputWithBadges(input);
      }
      process.stdout.write(inputDisplay);

      // 3. Write dropdown lines below input if present
      if (dropdownLines.length > 0) {
        for (const dl of dropdownLines) {
          process.stdout.write(`\n${dl}`);
        }
      }

      // 4. Calculate cursor position
      // Calculate how many wrapped lines inputDisplay occupies
      const plainAll = stripAnsi(inputDisplay);
      const allLines = plainAll.split('\n');
      let totalInputLines = 0;
      for (const l of allLines) {
        totalInputLines += l.length === 0 ? 1 : Math.max(1, Math.floor((l.length - 1) / cols) + 1);
      }

      // Calculate where the cursor is inside inputDisplay
      const plainBefore = stripAnsi(pc.dim('│') + '  ' + formatInputWithBadges(input.slice(0, cursorPos)));
      const linesBefore = plainBefore.split('\n');
      let curLine = 0;
      for (let i = 0; i < linesBefore.length - 1; i++) {
        const l = linesBefore[i];
        curLine += l.length === 0 ? 1 : Math.max(1, Math.floor((l.length - 1) / cols) + 1);
      }
      const lastLineSegment = linesBefore[linesBefore.length - 1];
      const wrapOffsetInLastLine = Math.floor(lastLineSegment.length / cols);
      curLine += wrapOffsetInLastLine;
      const curCol = lastLineSegment.length % cols;

      // Current line after writing dropdown is: (totalInputLines - 1) + dropdownLines.length
      const totalBottomLine = (totalInputLines - 1) + dropdownLines.length;
      const linesUp = totalBottomLine - curLine;

      if (linesUp > 0) {
        process.stdout.write(`\x1b[${linesUp}A`);
      }
      process.stdout.write('\r');
      if (curCol > 0) {
        process.stdout.write(`\x1b[${curCol}C`);
      }

      lastCursorLine = curLine;
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

    function clearBoxAndExit(finalInput: string) {
      if (lastCursorLine > 0) {
        process.stdout.write(`\x1b[${lastCursorLine}A`);
      }
      process.stdout.write('\r\x1b[J');

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

    async function handlePaste(pastedContent: string) {
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
      } else {
        input = input.slice(0, cursorPos) + normalized + input.slice(cursorPos);
        cursorPos += normalized.length;
        selectedIndex = 0;
        render();
      }
    }

    interface TagSpan {
      start: number;
      end: number;
      tag: string;
    }

    function getTagSpans(): TagSpan[] {
      const spans: TagSpan[] = [];
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

    function onData(chunk: Buffer) {
      if (disposed) return;
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
        }).catch(() => {});
        return;
      }

      // Ctrl+C
      if (str === '\x03' || (str.length === 1 && str.charCodeAt(0) === 3)) {
        if (lastCursorLine > 0) {
          process.stdout.write(`\x1b[${lastCursorLine}A`);
        }
        process.stdout.write('\r\x1b[J\n');
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
            } else {
              process.stdout.write(`\r\x1b[2K${pc.yellow('⚠️  No image in clipboard. Take a screenshot first (Win+Shift+S)\n')}`);
              input = '';
              cursorPos = 0;
              render();
            }
          }).catch(() => {});
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
        } else {
          if (lastCursorLine > 0) {
            process.stdout.write(`\x1b[${lastCursorLine}A`);
          }
          process.stdout.write('\r\x1b[J');
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
        } else if (GLOBAL_PROMPT_HISTORY.length > 0 && historyIndex > 0) {
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
        } else if (historyIndex < GLOBAL_PROMPT_HISTORY.length) {
          historyIndex++;
          if (historyIndex === GLOBAL_PROMPT_HISTORY.length) {
            input = tempDraft;
          } else {
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
          } else {
            cursorPos--;
            const inside = spans.find(s => cursorPos > s.start && cursorPos < s.end);
            if (inside) cursorPos = inside.start;
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
          } else {
            cursorPos++;
            const inside = spans.find(s => cursorPos > s.start && cursorPos < s.end);
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

      // Delete key (\x1b[3~)
      if (str === '\x1b[3~') {
        if (cursorPos < input.length) {
          const spans = getTagSpans();
          const startingSpan = spans.find(s => s.start === cursorPos);
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

      // Backspace
      if (str === '\x08' || str === '\x7f' || (str.length === 1 && (str.charCodeAt(0) === 8 || str.charCodeAt(0) === 127))) {
        if (cursorPos > 0) {
          const spans = getTagSpans();
          const endingSpan = spans.find(s => s.end === cursorPos);
          if (endingSpan) {
            input = input.slice(0, endingSpan.start) + input.slice(endingSpan.end);
            cursorPos = endingSpan.start;
          } else {
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
