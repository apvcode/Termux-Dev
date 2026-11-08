import { isTermux, notificationBody, notifyDevice, sendDesktopNotification } from './notify.js';

/**
 * "devx is waiting for you": a notification when the agent needs an answer (an approval card, a
 * question) and the person is not looking at the terminal. A finished task is announced elsewhere
 * (the end of a turn); this is for the moments when the work is stopped until someone answers.
 */

let tracking = false;
let focused = true;

/**
 * The terminal reports when it loses and gets the focus (while a task runs). Without those reports
 * devx cannot tell whether anybody is looking, and a notification that pops up in front of someone who is
 * typing in the terminal is worse than none, so nothing is sent then.
 */
export function setFocusTracking(on: boolean): void {
  tracking = on;
  // A task starts because the person just pressed Enter here.
  if (on) focused = true;
}

export function setTerminalFocused(value: boolean): void {
  focused = value;
}

export function isTerminalFocused(): boolean {
  return focused;
}

export interface AttentionDeps {
  termux?: boolean;
  desktop?: typeof sendDesktopNotification;
  device?: typeof notifyDevice;
}

/**
 * Tells the person that devx waits for them. Returns whether something was sent. Never throws.
 * On Termux (Android) a notification always goes out: the person is most likely in another app, and
 * the phone cannot tell devx. On a desktop it goes out only when the terminal is not in front.
 */
export function notifyAttention(title: string, body: string, deps: AttentionDeps = {}): boolean {
  try {
    const text = notificationBody(body, 140) || title;
    if (deps.termux ?? isTermux()) {
      (deps.device ?? notifyDevice)(title, text);
      return true;
    }
    if (!tracking || focused) return false;
    const via = (deps.desktop ?? sendDesktopNotification)(title, text);
    // No desktop notification service: the terminal bell at least marks the window as asking for attention.
    if (via === 'none') (deps.device ?? notifyDevice)(title, text);
    return true;
  } catch {
    return false;
  }
}

/** What an approval card asks, in a line for a notification: `bash: npm test`. */
export function approvalNotice(title: string, lines: string[], danger?: boolean): { title: string; body: string } {
  const first = (lines.find((l) => l.trim()) ?? '').trim();
  return {
    title: danger ? 'devx · dangerous action, approval needed' : 'devx · approval needed',
    body: first ? `${title}: ${first}` : title,
  };
}

/** The question the agent asks, for a notification (and how many more follow). */
export function questionNotice(questions: Array<{ question?: string }>): { title: string; body: string } {
  const first = String(questions[0]?.question ?? '').trim();
  const more = questions.length > 1 ? ` (+${questions.length - 1} more)` : '';
  return { title: 'devx · question for you', body: `${first || 'The agent has a question'}${more}` };
}
