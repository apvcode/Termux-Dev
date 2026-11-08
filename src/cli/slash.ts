import fs from 'fs';

/** The first word of a message. */
export function firstToken(text: string): string {
  return text.trimStart().split(/\s/, 1)[0] ?? '';
}

/**
 * A message that starts with a file path, not with a command: `/home/me/boot.img flash this`.
 *
 * A command is one word after the slash (`/model`), so a first word with another slash in it is a path.
 * A one-part path (`/tmp check it`) is a path when it exists on disk: pass `exists` where that can be
 * asked (when the line is sent); while typing, only the shape counts.
 */
export function startsWithPath(text: string, exists?: (path: string) => boolean): boolean {
  const token = firstToken(text);
  if (!token.startsWith('/')) return false;
  if (token.slice(1).includes('/')) return true;
  return !!exists && token.length > 1 && exists(token);
}

/** `exists` for `startsWithPath`: a real file or folder. */
export function pathExists(path: string): boolean {
  try {
    return fs.existsSync(path);
  } catch {
    return false;
  }
}

/** Every command the command loop knows (a custom command of the project is found separately). */
export const KNOWN_COMMANDS = [
  '/new', '/reset', '/resume', '/session', '/sessions', '/history',
  '/theme', '/themes', '/usage', '/export', '/mcp',
  '/settings', '/update', '/model', '/provider', '/providers',
  '/plan', '/agent', '/image', '/serve', '/memory', '/permissions', '/copy', '/rename', '/title', '/rewind', '/undo',
  '/diff', '/commit', '/status', '/compact', '/init', '/doctor',
  '/config', '/clear', '/exit', '/quit', '/help', '/effort', '/tasks', '/bg', '/jobs',
];

/**
 * The commands that run at once while the agent works: they only show something or change a setting and
 * need nothing of the conversation that is running. Every other command waits for the end of the turn
 * (it changes the conversation: /compact, /new, /undo, /plan ...).
 */
export const LIVE_COMMANDS = new Set([
  '/settings', '/help', '/theme', '/themes', '/tasks', '/bg', '/jobs', '/doctor', '/status',
  '/permissions', '/usage', '/mcp', '/config', '/model', '/effort', '/provider', '/providers',
]);
