import { Message } from './types.js';

/**
 * The name of a chat: a few words made by the model from the first exchange, shown in the title of
 * the terminal window and in the list of saved sessions. Everything here is a pure function except
 * the one call to the model, which is given in (`chat`) so that it can be tested.
 */

export const TITLE_SYMBOL = '◈';
export const BASE_TITLE = 'devx';

const MAX_TITLE_CHARS = 40;

/** What the model is asked: a short title in the language of the user, nothing else. */
export const TITLE_SYSTEM_PROMPT =
  'You name chats. Write a title of 2 to 5 words for the conversation below, in the language the user writes in. ' +
  'No quotes, no trailing punctuation, no prefix such as "Title:". Output only the title.';

/** Cleans what the model returned: one line, no quotes or markup, short. Empty when nothing usable is left. */
export function cleanTitle(raw: string | undefined | null): string {
  let title = String(raw ?? '')
    .replace(/<think>[\s\S]*?<\/think>/gi, '')
    .split('\n')
    .map((l) => l.trim())
    .find((l) => l.length > 0) ?? '';
  title = title
    .replace(/^(?:title|название|заголовок)\s*[:：-]\s*/i, '')
    .replace(/^[\s"'`«»“”„*#_>\-]+|[\s"'`«»“”„*_.:;!?…]+$/g, '')
    .replace(/\s+/g, ' ');
  if (Array.from(title).length > MAX_TITLE_CHARS) {
    const cut = Array.from(title).slice(0, MAX_TITLE_CHARS).join('');
    title = cut.replace(/\s+\S*$/, '') || cut;
    title = title.replace(/[\s,;:.\-]+$/, '');
  }
  return title;
}

/** A stand-in until the model has named the chat: the start of the first message. */
export function provisionalTitle(firstUserMessage: string): string {
  return cleanTitle(firstUserMessage.replace(/\s+/g, ' ').slice(0, 80));
}

export interface TitleChat {
  chat(request: { messages: Message[]; signal?: AbortSignal }): Promise<{ content?: string | null }>;
}

/**
 * Asks the model for the name. Never throws: on a failure or a timeout the answer is ''. When the
 * time is up the request is cut off, so a slow thinking model does not go on (and bill) for a
 * title nobody waits for.
 */
export async function generateChatTitle(provider: TitleChat, firstUser: string, firstReply: string, timeoutMs = 20000): Promise<string> {
  const sample = `User: ${firstUser.slice(0, 600)}\n\nAssistant: ${firstReply.slice(0, 600)}`;
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const answer = await Promise.race([
      provider.chat({
        messages: [
          { role: 'system', content: TITLE_SYSTEM_PROMPT },
          { role: 'user', content: sample },
        ],
        signal: controller.signal,
      }),
      // Also for a provider that ignores the signal: the title is given up on when the time is up.
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => {
          controller.abort();
          reject(new Error('timeout'));
        }, timeoutMs);
        timer.unref?.();
      }),
    ]);
    return cleanTitle(answer.content);
  } catch {
    return '';
  } finally {
    clearTimeout(timer);
  }
}

// ───────────────────────── terminal window title ─────────────────────────

/** The escape sequence that sets the title of the terminal window (OSC 0): `◈ Short title`. */
export function terminalTitleSequence(title: string): string {
  const clean = title.replace(/[\x00-\x1f\x7f]/g, ' ').replace(/\s+/g, ' ').trim();
  return `\x1b]0;${clean ? `${TITLE_SYMBOL} ${clean}` : BASE_TITLE}\x07`;
}

/** Remembers the title the terminal had (xterm title stack, also kitty, foot, wezterm), to give it back at exit. */
export const SAVE_TERMINAL_TITLE = '\x1b[22;0t';
export const RESTORE_TERMINAL_TITLE = '\x1b[23;0t';
