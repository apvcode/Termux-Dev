import { splitKeys } from './keys.js';
import { pauseSpinners, renderPermissionCard, renderPermissionChoice, } from './ui.js';
/**
 * Shows the approval card and waits for a decision:
 *   y / Enter on Allow  → allow        n / Esc / Ctrl+C → deny
 *   ← → Tab h l ↑ ↓     → move the selection
 * The card is erased once the answer is given (the tool line above stays), and any
 * running spinner is silenced meanwhile so the two never draw over each other.
 *
 * @param defaultAllow preselected answer; risky requests should start on Deny.
 */
export function askPermission(request, defaultAllow = true, io = { input: process.stdin, output: process.stdout }) {
    return new Promise((resolve) => {
        const { input, output } = io;
        const resumeSpinner = pauseSpinners();
        const cols = output.columns || 80;
        const card = renderPermissionCard(request, cols);
        let selected = defaultAllow ? 'allow' : 'deny';
        const wasRaw = !!input.isRaw;
        input.setRawMode?.(true);
        input.resume();
        output.write('\x1b[?25l' + card.join('\n') + '\n' + renderPermissionChoice(selected, cols));
        const redraw = () => output.write('\r\x1b[2K' + renderPermissionChoice(selected, cols));
        const finish = (allowed) => {
            input.off('data', onData);
            // Back to the first row of the card and wipe everything below it.
            output.write(`\r\x1b[2K${card.length > 0 ? `\x1b[${card.length}A` : ''}\r\x1b[J\x1b[?25h`);
            if (!wasRaw)
                input.setRawMode?.(false);
            resumeSpinner();
            resolve(allowed);
        };
        let done = false;
        const handleKey = (key) => {
            if (key === '\r' || key === '\n') {
                done = true;
                return finish(selected === 'allow');
            }
            if (key === 'y' || key === 'Y') {
                done = true;
                return finish(true);
            }
            if (key === 'n' || key === 'N' || key === 'q' || key === '\x1b' || key === '\x03') {
                done = true;
                return finish(false);
            }
            const toggle = key === '\t' || key === 'h' || key === 'l' ||
                key === '\x1b[C' || key === '\x1b[D' || key === '\x1b[A' || key === '\x1b[B' ||
                key === '\x1bOC' || key === '\x1bOD' || key === '\x1bOA' || key === '\x1bOB';
            if (toggle) {
                selected = selected === 'allow' ? 'deny' : 'allow';
                redraw();
            }
        };
        // Several key presses can arrive in one chunk (key repeat, batched input); the first
        // decisive key wins and the rest of the chunk is dropped.
        const onData = (chunk) => {
            for (const key of splitKeys(chunk.toString())) {
                if (done)
                    return;
                handleKey(key);
            }
        };
        input.on('data', onData);
    });
}
