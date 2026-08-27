import { spawn } from 'child_process';
export function isTermux() {
    return !!process.env.PREFIX?.includes('com.termux');
}
/**
 * Sends a notification and haptic vibration to the device.
 * On Termux: uses termux-notification and termux-vibrate via termux-api.
 * On Desktop: emits terminal bell \u0007.
 */
export function notifyDevice(title, message, options = { vibrate: true, sound: true }) {
    if (isTermux()) {
        try {
            // 1. Android notification via Termux API
            const notif = spawn('termux-notification', [
                '--title', title,
                '--content', message,
                '--id', 'devx_task_notif',
                '--priority', 'high'
            ], { stdio: 'ignore', detached: true });
            notif.on('error', () => { });
            notif.unref();
            // 2. Haptic vibration (150ms)
            if (options.vibrate !== false) {
                const vib = spawn('termux-vibrate', ['-d', '150'], { stdio: 'ignore', detached: true });
                vib.on('error', () => { });
                vib.unref();
            }
        }
        catch { }
    }
    else {
        // Desktop terminal bell
        if (options.sound !== false) {
            try {
                process.stdout.write('\u0007');
            }
            catch { }
        }
    }
}
