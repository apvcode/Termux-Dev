import fs from 'fs';
import path from 'path';
import { Tool } from '../core/types.js';
import { MAX_MEDIA_BYTES, mimeFor } from '../remote/media.js';

/** Turned on in a chat that is open on a phone (see SessionRuntime); in a plain terminal chat there is nobody to send to. */
let phoneAttached = false;
export function setPhoneAttached(on: boolean): void { phoneAttached = on; }
export function isPhoneAttached(): boolean { return phoneAttached; }

/**
 * `send_file`: hands a file or a picture of this computer to the person's phone, where it shows as a card they can open,
 * zoom, save or share.
 */
export const sendFileTool: Tool = {
  name: 'send_file',
  definition: {
    name: 'send_file',
    description:
      "Send a file from this computer to the person's phone, where it shows in the chat as a card (a picture can be opened and zoomed, any file saved or shared). " +
      'Use it when the person asks you to attach, send or show them a file, photo, screenshot, document or archive. Make the file first if needed (for example zip a folder with bash). Up to 25 MB.',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'The file: absolute, or relative to the working folder.' },
      },
      required: ['path'],
    },
  },
  validateArgs(args: any) {
    if (typeof args?.path !== 'string' || !args.path.trim()) throw new Error('path is required');
  },
  async execute(args: any): Promise<string> {
    const raw = String(args.path).trim().replace(/^~(?=\/|$)/, process.env.HOME ?? '~');
    const abs = path.resolve(process.cwd(), raw);
    let st: fs.Stats;
    try { st = fs.statSync(abs); } catch { return `Error: no such file: ${abs}`; }
    if (!st.isFile()) return `Error: ${abs} is not a file. Pack a folder into an archive first (for example with zip or tar).`;
    if (st.size > MAX_MEDIA_BYTES) return `Error: ${path.basename(abs)} is ${(st.size / 1048576).toFixed(1)} MB; the limit is ${MAX_MEDIA_BYTES / 1048576} MB.`;
    if (st.size === 0) return `Error: ${path.basename(abs)} is empty.`;
    return `[File ${abs} (${mimeFor(abs)}, ${st.size} bytes) was sent to the phone as "${path.basename(abs)}"]`;
  },
};
