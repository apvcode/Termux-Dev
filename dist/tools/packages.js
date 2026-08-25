import { spawn } from 'child_process';
import fsSync from 'fs';
export const installPackageTool = {
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
    validateArgs(args) {
        if (!args.package || typeof args.package !== 'string')
            throw new Error('package is required');
    },
    async execute(args) {
        let pkgManager = args.manager;
        if (!pkgManager) {
            if (fsSync.existsSync('package.json')) {
                if (fsSync.existsSync('pnpm-lock.yaml'))
                    pkgManager = 'pnpm';
                else if (fsSync.existsSync('yarn.lock'))
                    pkgManager = 'yarn';
                else
                    pkgManager = 'npm';
            }
            else if (fsSync.existsSync('requirements.txt') || fsSync.existsSync('pyproject.toml')) {
                pkgManager = 'pip';
            }
            else if (fsSync.existsSync('Cargo.toml')) {
                pkgManager = 'cargo';
            }
            else {
                pkgManager = 'npm';
            }
        }
        let cmd = '';
        if (pkgManager === 'npm') {
            cmd = `npm install ${args.dev ? '-D ' : ''}${args.package}`;
        }
        else if (pkgManager === 'yarn') {
            cmd = `yarn add ${args.dev ? '-D ' : ''}${args.package}`;
        }
        else if (pkgManager === 'pnpm') {
            cmd = `pnpm add ${args.dev ? '-D ' : ''}${args.package}`;
        }
        else if (pkgManager === 'pip') {
            cmd = `pip install ${args.package}`;
        }
        else if (pkgManager === 'cargo') {
            cmd = `cargo add ${args.package}`;
        }
        else {
            cmd = `npm install ${args.package}`;
        }
        return new Promise((resolve) => {
            const proc = spawn(cmd, { shell: true });
            let output = '';
            proc.stdout.on('data', (d) => { output += d.toString(); });
            proc.stderr.on('data', (d) => { output += d.toString(); });
            proc.on('close', (code) => {
                if (code === 0) {
                    resolve(`Successfully installed ${args.package} (${pkgManager})\n${output.trim()}`);
                }
                else {
                    resolve(`Installation exited with code ${code}:\n${output.trim()}`);
                }
            });
            proc.on('error', (err) => {
                resolve(`Failed to run ${cmd}: ${err.message}`);
            });
        });
    }
};
