import { Message } from '../core/types.js';
import { PickerItem } from './picker.js';
import { truncateWidth } from './width.js';
import { t } from '../core/i18n.js';

/**
 * /rewind: go back to one of your own messages. The conversation after it is removed (the model
 * forgets it too), and the message comes back into the input line to be edited.
 */

export const REWIND_CURRENT = -1;

/** "5 minutes ago" in the chosen language; empty when the message has no time (an old session). */
export function timeAgo(at: number | undefined, now: number = Date.now()): string {
  if (!at || !Number.isFinite(at)) return '';
  const seconds = Math.max(0, Math.round((now - at) / 1000));
  if (seconds < 60) return t('rewind.ago.now');
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return t('rewind.ago.min', { n: minutes });
  const hours = Math.round(minutes / 60);
  if (hours < 24) return t('rewind.ago.hour', { n: hours });
  return t('rewind.ago.day', { n: Math.round(hours / 24) });
}

/** What of a user message is shown: its first line, without the files that were attached to it. */
export function messageLabel(content: string, max = 52): string {
  const typed = content.split('\n\n--- Attached Files ---')[0];
  const first = typed.split('\n').map((l) => l.trim()).find((l) => l.length > 0) ?? '';
  return truncateWidth(first.replace(/\s+/g, ' '), max);
}

/** The text that goes back into the input line. */
export function messageDraft(content: string): string {
  return content.split('\n\n--- Attached Files ---')[0].trim();
}

export interface RewindEntry {
  /** Index in the history. */
  index: number;
  message: Message;
}

export function userEntries(messages: Message[]): RewindEntry[] {
  const out: RewindEntry[] = [];
  messages.forEach((message, index) => {
    if (message.role === 'user') out.push({ index, message });
  });
  return out;
}

/** The items of the menu: your messages, oldest first, and "(current)" last, where the cursor starts. */
export function rewindItems(messages: Message[], now: number = Date.now()): Array<PickerItem<number>> {
  const entries = userEntries(messages);
  const items: Array<PickerItem<number>> = entries.map((e, n) => {
    const later = entries.length - 1 - n;
    const parts = [timeAgo(e.message.at, now), later > 0 ? t('rewind.later', { n: later }) : ''].filter(Boolean);
    return { value: e.index, label: messageLabel(e.message.content), detail: parts.join(' · ') };
  });
  items.push({ value: REWIND_CURRENT, label: t('rewind.current'), detail: '' });
  return items;
}
