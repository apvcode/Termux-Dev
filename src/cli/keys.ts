// One terminal "key": a full escape sequence (arrows, Home, Delete...), a lone Esc, or a
// single character. The `u` flag keeps characters outside the BMP in one piece.
const KEY = /\x1b\[[0-9;?]*[ -/]*[@-~]|\x1bO[\s\S]|\x1b|[\s\S]/gu;

/**
 * Splits raw input into separate key presses.
 *
 * A terminal may deliver several keys in one chunk (holding an arrow key, a fast typist, a
 * slow connection, Termux batching input), for example "\x1b[B\x1b[B\x1b[B" for three
 * presses of Down. Code that compares a whole chunk with one key would miss them all.
 */
export function splitKeys(chunk: string): string[] {
  return chunk.match(KEY) ?? [];
}
