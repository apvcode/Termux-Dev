/**
 * A block of terminal rows that is redrawn in place.
 *
 * The cursor rests on the last row of the block. A redraw goes back up to the first row,
 * rewrites every row and wipes the rows left over from a taller previous version, so the
 * screen never grows however often the content changes. The cursor is hidden while the
 * block is on screen.
 */
export class LiveBlock {
    out;
    rows = 0;
    constructor(out) {
        this.out = out;
    }
    get height() {
        return this.rows;
    }
    draw(lines) {
        let buf = '\x1b[?25l';
        if (this.rows > 1)
            buf += `\x1b[${this.rows - 1}A`;
        buf += '\r';
        lines.forEach((line, i) => {
            buf += '\x1b[2K' + line + '\x1b[0m' + (i < lines.length - 1 ? '\n' : '');
        });
        let extra = 0;
        for (let k = lines.length; k < this.rows; k++) {
            buf += '\n\x1b[2K';
            extra++;
        }
        if (extra > 0)
            buf += `\x1b[${extra}A`;
        this.out.write(buf);
        this.rows = lines.length;
    }
    /** Removes the block from the screen and shows the cursor again. */
    erase() {
        if (this.rows === 0) {
            this.out.write('\x1b[?25h');
            return;
        }
        this.out.write(`\r${this.rows > 1 ? `\x1b[${this.rows - 1}A` : ''}\x1b[J\x1b[?25h`);
        this.rows = 0;
    }
}
