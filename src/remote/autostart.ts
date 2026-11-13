import { execFileSync } from 'child_process';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath } from 'url';

/**
 * Starting devx Remote together with the computer, so that the phone can connect without anyone opening a terminal.
 * Linux: a systemd user service. macOS: a launchd agent. Windows: a hidden script in the Startup folder.
 * (Termux has none of these: there the service is made by hand, see `devx remote service`.)
 */

export interface AutostartPlan {
  /** The file that is written. */
  file: string;
  text: string;
  /** Commands that switch it on / off (run in this order). */
  enable: string[][];
  disable: string[][];
}

/** The program that starts the host: this node and the devx entry file. */
export function defaultEntry(): string {
  return fileURLToPath(new URL('../../bin/devx.js', import.meta.url));
}

export function autostartPlan(entry: string = defaultEntry(), platform: NodeJS.Platform = process.platform, home: string = os.homedir(), node: string = process.execPath): AutostartPlan | null {
  if (platform === 'linux') {
    const file = path.join(home, '.config', 'systemd', 'user', 'devx-remote.service');
    return {
      file,
      text: `[Unit]\nDescription=devx remote host (control devx from your phone)\nAfter=network-online.target\n\n[Service]\nExecStart=${node} ${entry} remote daemon\nRestart=on-failure\nRestartSec=5\n\n[Install]\nWantedBy=default.target\n`,
      enable: [['systemctl', '--user', 'daemon-reload'], ['systemctl', '--user', 'enable', '--now', 'devx-remote.service']],
      disable: [['systemctl', '--user', 'disable', '--now', 'devx-remote.service']],
    };
  }
  if (platform === 'darwin') {
    const file = path.join(home, 'Library', 'LaunchAgents', 'dev.devx.remote.plist');
    return {
      file,
      text: `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0"><dict>\n<key>Label</key><string>dev.devx.remote</string>\n<key>ProgramArguments</key><array><string>${node}</string><string>${entry}</string><string>remote</string><string>daemon</string></array>\n<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>\n</dict></plist>\n`,
      enable: [['launchctl', 'load', '-w', file]],
      disable: [['launchctl', 'unload', '-w', file]],
    };
  }
  if (platform === 'win32') {
    const appData = process.env.APPDATA || path.join(home, 'AppData', 'Roaming');
    const file = path.join(appData, 'Microsoft', 'Windows', 'Start Menu', 'Programs', 'Startup', 'devx-remote.vbs');
    // wscript starts it without a console window.
    return {
      file,
      text: `CreateObject("WScript.Shell").Run """${node}"" ""${entry}"" remote daemon", 0, False\r\n`,
      enable: [],
      disable: [],
    };
  }
  return null;
}

export type Runner = (command: string[]) => void;

const run: Runner = (command) => {
  execFileSync(command[0], command.slice(1), { stdio: 'ignore', timeout: 20000 });
};

/** Is it set up (the file exists)? */
export function autostartOn(plan: AutostartPlan | null = autostartPlan()): boolean {
  return !!plan && fs.existsSync(plan.file);
}

/** Writes the file and switches it on; the host is started at once where the system can do that. */
export function enableAutostart(plan: AutostartPlan | null = autostartPlan(), runner: Runner = run): void {
  if (!plan) throw new Error('Starting with the computer is set up for Linux, macOS and Windows. In Termux make a service that runs: devx remote daemon');
  fs.mkdirSync(path.dirname(plan.file), { recursive: true });
  fs.writeFileSync(plan.file, plan.text);
  for (const command of plan.enable) runner(command);
}

/** Switches it off and removes the file. A switch-off that fails (it was not on) does not stop the removal. */
export function disableAutostart(plan: AutostartPlan | null = autostartPlan(), runner: Runner = run): void {
  if (!plan) return;
  for (const command of plan.disable) {
    try { runner(command); } catch { /* it was not running */ }
  }
  fs.rmSync(plan.file, { force: true });
}
