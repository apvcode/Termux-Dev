import { splitKeys } from './keys.js';
import {
  PermissionAnswer,
  PermissionOffer,
  PermissionRequest,
  pauseSpinners,
  renderPermissionCard,
  renderPermissionChoice,
} from './ui.js';

export interface PermissionInput {
  isRaw?: boolean;
  setRawMode?(mode: boolean): unknown;
  resume(): unknown;
  on(event: 'data', listener: (chunk: Buffer | string) => void): unknown;
  off(event: 'data', listener: (chunk: Buffer | string) => void): unknown;
}

export interface PermissionOutput {
  write(chunk: string): unknown;
  columns?: number;
}

/** The answers in the order the arrow keys go through them. */
function choices(offer: PermissionOffer): PermissionAnswer[] {
  return ['allow', ...(offer.session ? ['session' as const] : []), ...(offer.always ? ['always' as const] : []), 'deny'];
}

/**
 * Shows the approval card and waits for a decision:
 *   y / Enter on Allow  → allow once     n / Esc / Ctrl+C → deny
 *   s → allow for this session           a → allow always (only when the card offers them)
 *   ← → Tab h l ↑ ↓     → move the selection
 * The card is erased once the answer is given (the tool line above stays), and any
 * running spinner is silenced meanwhile so the two never draw over each other.
 *
 * @param defaultAnswer preselected answer; risky requests should start on Deny.
 */
export function askPermissionDecision(
  request: PermissionRequest,
  defaultAnswer: PermissionAnswer = 'allow',
  io: { input: PermissionInput; output: PermissionOutput } = { input: process.stdin, output: process.stdout },
  offer: PermissionOffer = {}
): Promise<PermissionAnswer> {
  return new Promise((resolve) => {
    const { input, output } = io;
    const resumeSpinner = pauseSpinners();
    const cols = output.columns || 80;
    const card = renderPermissionCard(request, cols);
    const order = choices(offer);
    let selected: PermissionAnswer = order.includes(defaultAnswer) ? defaultAnswer : 'deny';

    const wasRaw = !!input.isRaw;
    input.setRawMode?.(true);
    input.resume();

    output.write('\x1b[?25l' + card.join('\n') + '\n' + renderPermissionChoice(selected, cols, offer));

    const redraw = () => output.write('\r\x1b[2K' + renderPermissionChoice(selected, cols, offer));

    const finish = (answer: PermissionAnswer) => {
      input.off('data', onData);
      // Back to the first row of the card and wipe everything below it.
      output.write(`\r\x1b[2K${card.length > 0 ? `\x1b[${card.length}A` : ''}\r\x1b[J\x1b[?25h`);
      if (!wasRaw) input.setRawMode?.(false);
      resumeSpinner();
      resolve(answer);
    };

    let done = false;
    const handleKey = (key: string) => {
      if (key === '\r' || key === '\n') { done = true; return finish(selected); }
      if (key === 'y' || key === 'Y') { done = true; return finish('allow'); }
      if ((key === 's' || key === 'S') && offer.session) { done = true; return finish('session'); }
      if ((key === 'a' || key === 'A') && offer.always) { done = true; return finish('always'); }
      if (key === 'n' || key === 'N' || key === 'q' || key === '\x1b' || key === '\x03') { done = true; return finish('deny'); }

      const back = key === '\x1b[D' || key === '\x1bOD' || key === 'h' || key === '\x1b[A' || key === '\x1bOA';
      const forward = key === '\t' || key === 'l' || key === '\x1b[C' || key === '\x1bOC' || key === '\x1b[B' || key === '\x1bOB';
      if (back || forward) {
        const i = order.indexOf(selected);
        selected = order[(i + (back ? -1 : 1) + order.length) % order.length];
        redraw();
      }
    };

    // Several key presses can arrive in one chunk (key repeat, batched input); the first
    // decisive key wins and the rest of the chunk is dropped.
    const onData = (chunk: Buffer | string) => {
      for (const key of splitKeys(chunk.toString())) {
        if (done) return;
        handleKey(key);
      }
    };

    input.on('data', onData);
  });
}

/** The two-answer form: true when the action is allowed (once). */
export function askPermission(
  request: PermissionRequest,
  defaultAllow = true,
  io: { input: PermissionInput; output: PermissionOutput } = { input: process.stdin, output: process.stdout }
): Promise<boolean> {
  return askPermissionDecision(request, defaultAllow ? 'allow' : 'deny', io).then((answer) => answer !== 'deny');
}
