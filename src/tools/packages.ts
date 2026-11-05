import { spawn } from 'child_process';
import fsSync from 'fs';
import { Tool } from '../core/types.js';

export const installPackageTool: Tool = {
  name: 'install_package',
  definition: {
    name: 'install_package',
    description: 'Install a package or dependency using npm, pip, or cargo in the current project.',
    parameters: {
      type: 'object',
      properties: {
        package: { type: 'string', description: 'Package name to install (e.g. "express", "axios", "three", "pygame")' },
        dev: { type: 'boolean', description: 'Whether to install as a dev dependency (for npm)' },
        manager: { type: 'string', enum: ['npm', 'pip', 'yarn', 'pnpm', 'cargo'], description: 'Package manager to use (auto-detected if omitted)' }
      },
      required: ['package']
    }
  },
  validateArgs(args: any) {
    if (!args.package || typeof args.package !== 'string') throw new Error('package is required');
    const sanitized = args.package.trim();
    if (!/^[@a-zA-Z0-9_\-./=><^~+:[\] ]+$/.test(sanitized) || /[;&|`$\n\r()]/.test(sanitized)) {
      throw new Error(`Invalid package name: "${args.package}". Invalid characters or potential command injection detected.`);
    }
    // A token starting with "-" would be parsed by the package manager as an option
    // (--registry=..., --index-url=..., -r file), not as a package name.
    if (sanitized.split(/\s+/).some((token: string) => token.startsWith('-'))) {
      throw new Error(`Invalid package name: "${args.package}". Command line options are not allowed, pass package names only.`);
    }
    // On Windows the package manager is started through cmd.exe, where these are redirection / escape characters.
    if (process.platform === 'win32' && /[<>^]/.test(sanitized)) {
      throw new Error(`Invalid package name: "${args.package}". Version ranges with < > ^ are not supported on Windows, use an exact version (pkg@1.2.3 or pkg==1.2.3).`);
    }
    if (args.manager !== undefined && !['npm', 'pip', 'yarn', 'pnpm', 'cargo'].includes(args.manager)) {
      throw new Error(`Unsupported package manager: "${args.manager}".`);
    }
  },
  async execute(args: { package: string; dev?: boolean; manager?: string }) {
    let pkgManager = args.manager;
    if (!pkgManager) {
      if (fsSync.existsSync('package.json')) {
        if (fsSync.existsSync('pnpm-lock.yaml')) pkgManager = 'pnpm';
        else if (fsSync.existsSync('yarn.lock')) pkgManager = 'yarn';
        else pkgManager = 'npm';
      } else if (fsSync.existsSync('requirements.txt') || fsSync.existsSync('pyproject.toml')) {
        pkgManager = 'pip';
      } else if (fsSync.existsSync('Cargo.toml')) {
        pkgManager = 'cargo';
      } else {
        pkgManager = 'npm';
      }
    }

    let executable = 'npm';
    let cmdArgs: string[] = [];

    const pkgs = args.package.trim().split(/\s+/);

    if (pkgManager === 'npm') {
      executable = 'npm';
      cmdArgs = ['install'];
      if (args.dev) cmdArgs.push('-D');
      cmdArgs.push(...pkgs);
    } else if (pkgManager === 'yarn') {
      executable = 'yarn';
      cmdArgs = ['add'];
      if (args.dev) cmdArgs.push('-D');
      cmdArgs.push(...pkgs);
    } else if (pkgManager === 'pnpm') {
      executable = 'pnpm';
      cmdArgs = ['add'];
      if (args.dev) cmdArgs.push('-D');
      cmdArgs.push(...pkgs);
    } else if (pkgManager === 'pip') {
      executable = 'pip';
      cmdArgs = ['install'];
      // Termux's Python is "externally managed" and has no system package manager that
      // could be broken. On a regular Linux/macOS system this flag would override PEP 668
      // and may damage the OS Python, so it is only used on Termux.
      if (process.env.PREFIX?.includes('com.termux')) {
        cmdArgs.push('--break-system-packages');
      }
      cmdArgs.push(...pkgs);
    } else if (pkgManager === 'cargo') {
      executable = 'cargo';
      cmdArgs = ['add', ...pkgs];
    } else {
      executable = 'npm';
      cmdArgs = ['install', ...pkgs];
    }

    const isWin = process.platform === 'win32';
    return new Promise((resolve) => {
      const proc = spawn(executable, cmdArgs, { shell: isWin });
      let output = '';

      proc.stdout.on('data', (d) => { output += d.toString(); });
      proc.stderr.on('data', (d) => { output += d.toString(); });

      proc.on('close', (code) => {
        if (code === 0) {
          resolve(`Successfully installed ${args.package} (${pkgManager})\n${output.trim()}`);
        } else {
          resolve(`Installation exited with code ${code}:\n${output.trim()}`);
        }
      });

      proc.on('error', (err) => {
        resolve(`Failed to run ${executable} ${cmdArgs.join(' ')}: ${err.message}`);
      });
    });
  }
};
