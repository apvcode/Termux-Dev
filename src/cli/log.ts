import { palette, renderNotice } from './ui.js';
import { stripAnsi } from './width.js';

/**
 * One-line messages in the style of the rest of devx (`  ! text`, `  ✓ text`), instead of the
 * boxed log of the prompt library. Same names, so a call reads as before: `log.warn('…')`.
 * Colours inside the message are dropped: the kind of the message decides the colour.
 */
type Kind = 'info' | 'ok' | 'warn' | 'error';

const print = (kind: Kind, message: string) => console.log(renderNotice(kind, stripAnsi(message)));

export const log = {
  info: (message: string) => print('info', message),
  success: (message: string) => print('ok', message),
  warn: (message: string) => print('warn', message),
  error: (message: string) => print('error', message),
  message: (message: string) => print('info', message),
};

/** A titled block of lines (the command list, the config), rows kept as they are. */
export function note(text: string, title = ''): void {
  console.log();
  if (title) console.log('  ' + palette.bold(palette.accent(title)));
  for (const row of stripAnsi(text).split('\n')) console.log('    ' + palette.muted(row));
  console.log();
}
