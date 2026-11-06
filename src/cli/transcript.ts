import { Message } from '../core/types.js';
import { getActionDescription } from '../core/loop.js';
import { formatDiffBox } from './diff.js';
import { renderMarkdown } from './markdown.js';
import { palette, renderPlanCard, renderResultLine, renderTaskCard, renderToolLine, renderUserEcho, summarizeToolResult } from './ui.js';
import { displayWidth, truncateWidth, wrapText } from './width.js';
import { t } from '../core/i18n.js';

/**
 * The conversation as it is on screen, kept as items so it can be drawn again: ctrl+p and ctrl+o
 * fold and unfold the reasoning and the tool output of the WHOLE chat, also of what has
 * already scrolled by. The live output prints the same rows item by item as they happen.
 */
export type TranscriptItem =
  | { kind: 'user'; text: string }
  | { kind: 'thought'; text: string; sec: string; stats: string }
  | { kind: 'assistant'; text: string }
  | { kind: 'tool'; name: string; desc: string; result?: string }
  | { kind: 'raw'; rows: string[] };

export interface ViewOptions {
  cols: number;
  thinkingExpanded: boolean;
  outputExpanded: boolean;
  diffColors?: boolean;
}

const MAX_ECHO_LINES = 300;

/** The rows under a tool line: a plan card, a diff, or the output (one row folded, all of it unfolded). */
export function toolResultRows(name: string, result: string, opts: { cols: number; expanded: boolean; diffColors?: boolean }): string[] {
  const { cols } = opts;
  try {
    const parsed = JSON.parse(result);
    if (parsed && typeof parsed === 'object') {
      if (parsed.type === 'todo_list' && Array.isArray(parsed.tasks) && parsed.tasks.length > 0) return renderTaskCard(parsed.tasks, cols);
      if (parsed.type === 'plan_ready' && typeof parsed.summary === 'string') return renderPlanCard({ summary: parsed.summary, files: parsed.files, steps: parsed.steps }, cols);
      if (parsed.action === 'edit' && parsed.diffLines) {
        const box = formatDiffBox(parsed.path, parsed.diffLines, Math.min(cols - 4, 80), undefined, !!opts.diffColors);
        const rows = box.split('\n').map((l: string) => '    ' + l);
        if (parsed.summary) rows.push(renderResultLine(parsed.summary, 'ok', cols));
        return rows;
      }
      if (parsed.summary) return [renderResultLine(String(parsed.summary), 'ok', cols)];
    }
  } catch {}
  return summarizeToolResult(name, result, cols, opts.expanded);
}

export function assistantRows(text: string): string[] {
  const rows = renderMarkdown(text).replace(/\s+$/, '').split('\n');
  return rows.map((row, i) => (i === 0 ? '  ' + palette.accent('●') + ' ' : '    ') + row);
}

/** `  ∴ thought for 0.3s · 112 tok  (ctrl+p to expand)`, the hint dropped and the rest cut when the screen is narrow. */
export function collapsedThoughtLine(summary: string, hasText: boolean, cols: number): string {
  const room = Math.max(10, cols - 1 - 4);
  const hint = hasText ? `  (${t('view.expandThought')})` : '';
  const text = displayWidth(summary + hint) <= room ? summary + hint : truncateWidth(summary, room);
  return palette.muted('  ∴ ') + palette.faint(text);
}

function thoughtRows(item: Extract<TranscriptItem, { kind: 'thought' }>, opts: ViewOptions): string[] {
  const summary = `${t('status.thoughtFor', { sec: item.sec })}${item.stats}`;
  if (!opts.thinkingExpanded || !item.text.trim()) {
    return [collapsedThoughtLine(summary, !!item.text.trim(), opts.cols)];
  }
  const bar = palette.faint('  │ ');
  const body = item.text.trim().split('\n').flatMap((line) => (line.trim() === '' ? [''] : wrapText(line, Math.max(20, opts.cols - 8))));
  return [palette.muted(`  ∴ ${t('status.thinkingLine')}`), ...body.map((l) => bar + palette.muted(l)), palette.faint(`  ╰ ${summary}`)];
}

/** One item as terminal rows (without the blank row that separates it from its neighbour). */
export function renderItem(item: TranscriptItem, opts: ViewOptions): string[] {
  switch (item.kind) {
    case 'user': {
      const lines = item.text.replace(/\t/g, '  ').split('\n');
      const text = lines.length > MAX_ECHO_LINES ? [...lines.slice(0, MAX_ECHO_LINES), `… +${lines.length - MAX_ECHO_LINES} lines`].join('\n') : item.text;
      return renderUserEcho(text, opts.cols);
    }
    case 'thought':
      return thoughtRows(item, opts);
    case 'assistant':
      return assistantRows(item.text);
    case 'tool': {
      const rows = renderToolLine(item.desc, opts.cols, opts.outputExpanded).split('\n');
      if (item.result !== undefined) rows.push(...toolResultRows(item.name, item.result, { cols: opts.cols, expanded: opts.outputExpanded, diffColors: opts.diffColors }));
      return rows;
    }
    default:
      return item.rows;
  }
}

/** Whether a blank row goes between two neighbours: everywhere, except between two tool calls and after the user's own padded echo. */
export function gapBetween(prev: TranscriptItem | undefined, next: TranscriptItem): boolean {
  if (!prev) return false;
  if (prev.kind === 'user' || next.kind === 'user') return false;
  if (prev.kind === 'tool' && next.kind === 'tool') return false;
  return true;
}

export function renderTranscript(items: TranscriptItem[], opts: ViewOptions): string[] {
  const out: string[] = [];
  items.forEach((item, i) => {
    if (gapBetween(items[i - 1], item)) out.push('');
    out.push(...renderItem(item, opts));
  });
  return out;
}

/** A saved conversation as items (a resumed session has no reasoning texts: they were never stored). */
export function messagesToItems(messages: Message[]): TranscriptItem[] {
  const results = new Map<string, Message>();
  for (const m of messages) if (m.role === 'tool' && m.tool_call_id) results.set(m.tool_call_id, m);
  const items: TranscriptItem[] = [];
  for (const m of messages) {
    if (m.role === 'user') items.push({ kind: 'user', text: m.content.split('\n\n--- Attached Files ---')[0] });
    else if (m.role === 'assistant') {
      if (m.reasoning && m.reasoning.text.trim()) items.push({ kind: 'thought', text: m.reasoning.text, sec: m.reasoning.sec, stats: m.reasoning.stats });
      if (m.content && m.content.trim()) items.push({ kind: 'assistant', text: m.content });
      for (const call of m.tool_calls ?? []) {
        items.push({ kind: 'tool', name: call.name, desc: getActionDescription(call.name, call.arguments ?? {}), result: results.get(call.id)?.content });
      }
    }
  }
  return items;
}
