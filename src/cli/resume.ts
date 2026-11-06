import { Message } from '../core/types.js';
import { palette, renderNotice } from './ui.js';
import { displayWidth, truncateWidth } from './width.js';
import { messagesToItems, renderTranscript } from './transcript.js';

/**
 * How a resumed session looks: the same layout as a live one (it is drawn by the same code as the
 * conversation when ctrl+p / ctrl+o redraw it). The reasoning of each reply comes back too, folded
 * unless the switch is on.
 */
export interface ResumeInfo {
  title: string;
  shown: number;
  total: number;
  cols: number;
  thinkingExpanded?: boolean;
  outputExpanded?: boolean;
}

export function renderResumedMessages(messages: Message[], info: ResumeInfo): string[] {
  const { cols } = info;
  const label = info.title.replace(/\s+/g, ' ').trim();
  const count = info.total > info.shown ? `${info.shown} / ${info.total}` : String(info.total);
  const head = `  ${palette.faint('╌')} ${palette.muted(truncateWidth(label, Math.max(10, cols - 14 - displayWidth(count))))}  ${palette.faint(count)}`;
  const body = renderTranscript(messagesToItems(messages), {
    cols,
    thinkingExpanded: !!info.thinkingExpanded,
    outputExpanded: !!info.outputExpanded,
  });
  return ['', head, '', ...body, '', renderNotice('ok', `resumed · ${info.total} messages`, cols), ''];
}
