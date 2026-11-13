import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { autostartOn, autostartPlan, disableAutostart, enableAutostart } from '../../src/remote/autostart.js';

describe('autostart', () => {
  let home: string;
  beforeEach(() => { home = fs.mkdtempSync(path.join(os.tmpdir(), 'devx-autostart-')); });
  afterEach(() => { fs.rmSync(home, { recursive: true, force: true }); });

  it('makes a systemd user service on Linux that runs the daemon', () => {
    const plan = autostartPlan('/opt/devx/bin/devx.js', 'linux', home, '/usr/bin/node')!;
    expect(plan.file).toBe(path.join(home, '.config', 'systemd', 'user', 'devx-remote.service'));
    expect(plan.text).toContain('ExecStart=/usr/bin/node /opt/devx/bin/devx.js remote daemon');
    expect(plan.text).toContain('WantedBy=default.target');
  });

  it('makes a launch agent on macOS', () => {
    const plan = autostartPlan('/opt/devx/bin/devx.js', 'darwin', home, '/usr/bin/node')!;
    expect(plan.file).toBe(path.join(home, 'Library', 'LaunchAgents', 'dev.devx.remote.plist'));
    expect(plan.text).toContain('<string>daemon</string>');
    expect(plan.text).toContain('RunAtLoad');
  });

  it('puts a hidden script in the Startup folder on Windows', () => {
    const plan = autostartPlan('C:\\devx\\bin\\devx.js', 'win32', home, 'C:\\node.exe')!;
    expect(plan.file.endsWith('devx-remote.vbs')).toBe(true);
    expect(plan.text).toContain('remote daemon');
    expect(plan.text).toContain(', 0, False');
  });

  it('has nothing for other systems', () => {
    expect(autostartPlan('/x', 'android' as NodeJS.Platform, home, 'node')).toBeNull();
    expect(autostartOn(null)).toBe(false);
    expect(() => enableAutostart(null)).toThrow();
  });

  it('writes the file and runs the switch-on commands, then switches off and removes the file', () => {
    const plan = autostartPlan('/opt/devx/bin/devx.js', 'linux', home, '/usr/bin/node')!;
    const ran: string[] = [];
    enableAutostart(plan, (c) => { ran.push(c.join(' ')); });
    expect(autostartOn(plan)).toBe(true);
    expect(ran).toEqual(['systemctl --user daemon-reload', 'systemctl --user enable --now devx-remote.service']);
    disableAutostart(plan, (c) => { ran.push(c.join(' ')); throw new Error('not running'); });
    expect(autostartOn(plan)).toBe(false);
    expect(ran[ran.length - 1]).toBe('systemctl --user disable --now devx-remote.service');
  });
});
