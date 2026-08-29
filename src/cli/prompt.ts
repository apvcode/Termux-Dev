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
    const msg = opts.message
      ? `${opts.message} ${pc.dim(`(Tab = ${opts.planMode ? 'AGENT' : 'PLAN'})`)}`
      : `Ask anything... ${pc.dim(`(Tab = ${opts.planMode ? 'AGENT' : 'PLAN'})`)}`;

    const theme = getCurrentTheme();
    console.log(theme.colorFn('◆') + '  ' + pc.bold(msg));

    let availableFiles: string[] = [];
    let customCommandsList: Array<{ cmd: string; desc: string }> = [];

    scanProjectFiles().then((f) => (availableFiles = f)).catch(() => {});
    CustomCommandManager.listCommands()
      .then((c) => (customCommandsList = c.map((x) => ({ cmd: x.cmd, desc: x.desc }))))
      .catch(() => {});

    const imageAttachments: Array<{ tag: string; fileName: string; filePath: string; sizeStr: string }> = [];
    const usedImageNames = new Set<string>();

    const completer = (line: string): [string[], string] => {
      const allCmds = [
        ...SLASH_COMMANDS.map((c) => c.cmd),
        ...customCommandsList.map((c) => c.cmd)
      ];

      // 1. Slash commands autocomplete
      if (line.startsWith('/')) {
        const q = line.trim().toLowerCase();
        const hits = allCmds.filter((cmd) => cmd.toLowerCase().startsWith(q));
        return [hits.length ? hits : allCmds, line];
      }

      // 2. @file autocomplete
      const atMatch = line.match(/@([a-zA-Z0-9_\-./]*)$/);
      if (atMatch && availableFiles.length > 0) {
        const query = atMatch[1].toLowerCase();
        const hits = availableFiles
          .filter((f) => f.toLowerCase().includes(query) || query === '')
          .slice(0, 15)
          .map((f) => `@${f}${f.endsWith('/') ? '' : ' '}`);
        return [hits, atMatch[0]];
      }

      return [[], line];
    };

    const rl = readline.createInterface({
      input: process.stdin,
      output: process.stdout,
      prompt: pc.dim('│') + '  ',
      completer,
      historySize: 100
    });

    // Populate history in readline
    if (GLOBAL_PROMPT_HISTORY.length > 0) {
      (rl as any).history = [...GLOBAL_PROMPT_HISTORY].reverse();
    }

    let isClosed = false;

    const cleanup = () => {
      if (isClosed) return;
      isClosed = true;
      process.stdin.removeListener('keypress', onKeypress);
      rl.close();
    };

    const onKeypress = (str: string, key: any) => {
      if (isClosed) return;

      // Tab key pressed
      if (key && key.name === 'tab') {
        const curLine = rl.line || '';
        // If line is empty or does not start with / or contain @, switch mode
        if (curLine.trim() === '' || (!curLine.startsWith('/') && !curLine.includes('@'))) {
          readline.cursorTo(process.stdout, 0);
          readline.clearLine(process.stdout, 0);
          cleanup();
          resolve(`__TOGGLE_MODE__:${curLine}`);
          return;
        }
      }

      // Ctrl+V or Ctrl+P: Clipboard image paste
      if ((key && key.ctrl && (key.name === 'v' || key.name === 'p')) || str === '\x16' || str === '\x10') {
        saveClipboardImage('image.png', usedImageNames)
          .then((imgRes) => {
            if (imgRes && !isClosed) {
              const tag = `[${imgRes.fileName} ${imgRes.sizeStr}]`;
              imageAttachments.push({
                tag,
                fileName: imgRes.fileName,
                filePath: imgRes.filePath,
                sizeStr: imgRes.sizeStr
              });
              usedImageNames.add(imgRes.fileName);
              rl.write(`${tag} `);
            }
          })
          .catch(() => {});
      }
    };

    if (process.stdin.isTTY) {
      readline.emitKeypressEvents(process.stdin, rl);
      process.stdin.on('keypress', onKeypress);
    }

    if (opts.initialValue) {
      rl.write(opts.initialValue);
    }

    rl.prompt();

    rl.on('line', async (rawLine: string) => {
      cleanup();

      let line = rawLine.trim();

      // Handle direct /image command
      if (line === '/image') {
        try {
          const imgRes = await saveClipboardImage('image.png', usedImageNames);
          if (imgRes) {
            const tag = `[${imgRes.fileName} ${imgRes.sizeStr}]`;
            imageAttachments.push({
              tag,
              fileName: imgRes.fileName,
              filePath: imgRes.filePath,
              sizeStr: imgRes.sizeStr
            });
            usedImageNames.add(imgRes.fileName);
            line = tag;
          } else {
            console.log(pc.yellow('⚠️  No image in clipboard. Take a screenshot first (Win+Shift+S)\n'));
            resolve('');
            return;
          }
        } catch {
          resolve('');
          return;
        }
      }

      // Check for pasted file path to image
      if (line.match(/\.(png|jpe?g|webp|gif|bmp)$/i)) {
        const imgRes = await processPastedFilePath(line, usedImageNames);
        if (imgRes) {
          const tag = `[${imgRes.fileName} ${imgRes.sizeStr}]`;
          imageAttachments.push({
            tag,
            fileName: imgRes.fileName,
            filePath: imgRes.filePath,
            sizeStr: imgRes.sizeStr
          });
          usedImageNames.add(imgRes.fileName);
          line = tag;
        }
      }

      // Expand image attachment tags to @path
      let expanded = line;
      for (const img of imageAttachments) {
        expanded = expanded.split(img.tag).join(`@${img.filePath.replace(/\\/g, '/')} `);
      }

      if (expanded.trim()) {
        if (
          GLOBAL_PROMPT_HISTORY.length === 0 ||
          GLOBAL_PROMPT_HISTORY[GLOBAL_PROMPT_HISTORY.length - 1] !== expanded.trim()
        ) {
          GLOBAL_PROMPT_HISTORY.push(expanded.trim());
        }
      }

      console.log();
      resolve(expanded);
    });

    rl.on('SIGINT', () => {
      cleanup();
      console.log();
      resolve('__CANCEL__');
    });
  });
}
