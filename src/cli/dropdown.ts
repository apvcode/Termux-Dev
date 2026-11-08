import { palette } from './ui.js';
import { padEndWidth, truncateWidth } from './width.js';

export interface DropdownRow {
  label: string;
  desc: string;
}

/** Rows of the list that fit under the frame: not more than 10, and never more than the screen has room for. */
export function dropdownPageSize(termRows: number): number {
  return Math.max(3, Math.min(10, termRows - 9));
}

/**
 * The list under the prompt: the window of rows around the selection, one row per item, and a last row
 * that says where in the list the selection is when not everything is shown (`3 / 33 · ↑↓ for more`).
 * Nothing is cut off the list itself: every item can be reached with the arrow keys.
 */
export function renderDropdown(items: DropdownRow[], selected: number, cols: number, termRows: number): string[] {
  if (items.length === 0) return [];
  const at = Math.max(0, Math.min(items.length - 1, selected));
  const page = dropdownPageSize(termRows);
  const shown = items.length > page ? page - 1 : items.length;   // one row is left for the position
  let start = 0;
  if (items.length > shown && at >= shown) start = Math.min(at - shown + 1, items.length - shown);
  const end = Math.min(start + shown, items.length);

  const labelWidth = Math.min(22, Math.max(8, Math.floor(cols * 0.4)));
  const descRoom = cols - 1 - 2 - 2 - labelWidth - 2;
  const out: string[] = [];
  for (let i = start; i < end; i++) {
    const item = items[i];
    const label = truncateWidth(item.label, labelWidth);
    const labelCell = padEndWidth(i === at ? palette.bold(palette.accent(label)) : palette.text(label), labelWidth);
    const desc = descRoom >= 6 ? truncateWidth(item.desc, descRoom) : '';
    out.push(`${i === at ? palette.accent('›') : ' '} ${labelCell}${desc ? '  ' + palette.faint(desc) : ''}`);
  }
  if (items.length > shown) out.push('  ' + palette.faint(truncateWidth(`${at + 1} / ${items.length} · ↑↓`, Math.max(8, cols - 4))));
  return out;
}
