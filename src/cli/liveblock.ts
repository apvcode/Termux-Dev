import { displayWidth } from './width.js';

export interface BlockOutput {
  write(chunk: string): unknown;
  columns?: number;
  on?: (event: string, listener: () => void) => unknown;
  off?: (event: string, listener: () => void) => unknown;
}

/**
 * A block of terminal rows that is redrawn in place.
 *
 * The cursor rests on the last row of the block. A redraw goes back up to the first row,
 * rewrites every row and wipes the rows left over from a taller previous version, so the
 * screen never grows however often the content changes. The cursor is hidden while the
 * block is on screen.
 *
 * The way back up is counted in rows of the screen, not in lines of the block: when the window
 * gets narrower the terminal wraps the lines it already drew onto more rows, and going up by the
 * number of lines would leave the top of the old block on screen (the block would show up twice).
 */
export class LiveBlock {
  /** Visible width of each line of the block as it was drawn. */
  private widths: number[] = [];

  constructor(private out: BlockOutput) {}

  /** Number of lines of the block on screen. */
  get height(): number {
    return this.widths.length;
  }

  private columns(): number {
    return Math.max(1, this.out.columns || process.stdout.columns || 80);
  }

  /** Rows of the screen the given lines take at the current width. */
  private rowsOf(widths: number[]): number {
    const cols = this.columns();
    return widths.reduce((n, w) => n + Math.max(1, Math.ceil(w / cols)), 0);
  }

  draw(lines: string[]): void {
    const before = this.rowsOf(this.widths);
    let buf = '\x1b[?25l';
    if (before > 1) buf += `\x1b[${before - 1}A`;
    buf += '\r';
    lines.forEach((line, i) => {
      buf += '\x1b[2K' + line + '\x1b[0m' + (i < lines.length - 1 ? '\n' : '');
    });
    const widths = lines.map((line) => displayWidth(line));
    const now = this.rowsOf(widths);
    let extra = 0;
    for (let k = now; k < before; k++) {
      buf += '\n\x1b[2K';
      extra++;
    }
    if (extra > 0) buf += `\x1b[${extra}A`;
    this.out.write(buf);
    this.widths = widths;
  }

  /** Removes the block from the screen and shows the cursor again. */
  erase(): void {
    const rows = this.rowsOf(this.widths);
    if (rows === 0) {
      this.out.write('\x1b[?25h');
      return;
    }
    this.out.write(`\r${rows > 1 ? `\x1b[${rows - 1}A` : ''}\x1b[J\x1b[?25h`);
    this.widths = [];
  }

  /**
   * Calls `redraw` when the window is resized, so the block is drawn again at the new width.
   * Returns the function that stops watching (call it when the panel closes).
   */
  watchResize(redraw: () => void): () => void {
    this.out.on?.('resize', redraw);
    return () => {
      this.out.off?.('resize', redraw);
    };
  }
}
