/**
 * The devx wordmark, drawn from a small pixel font: every letter is 5 pixels wide and 6 tall, and two
 * pixel rows share one terminal row with half blocks, so the pixels are square and the shapes stay clean.
 */
const FONT: Record<string, string[]> = {
  D: ['████ ', '█   █', '█   █', '█   █', '█   █', '████ '],
  E: ['█████', '█    ', '████ ', '█    ', '█    ', '█████'],
  V: ['█   █', '█   █', '█   █', '█   █', ' █ █ ', '  █  '],
  X: ['█   █', ' █ █ ', '  █  ', '  █  ', ' █ █ ', '█   █'],
};

export function wordmark(text = 'DEVX', gap = 1): string[] {
  const letters = Array.from(text).map((c) => FONT[c]);
  const rows = letters[0].length;
  const out: string[] = [];
  for (let r = 0; r < rows; r += 2) {
    let line = '';
    letters.forEach((glyph, i) => {
      if (i > 0) line += ' '.repeat(gap);
      for (let x = 0; x < glyph[r].length; x++) {
        const top = glyph[r][x] !== ' ';
        const bottom = glyph[r + 1][x] !== ' ';
        line += top && bottom ? '█' : top ? '▀' : bottom ? '▄' : ' ';
      }
    });
    out.push(line);
  }
  return out;
}

export const DEVX_LOGO = wordmark();
