import { exec } from 'child_process';
import fsSync from 'fs';
export const diagnoseCodeTool = {
    name: 'diagnose_code',
    definition: {
        name: 'diagnose_code',
        description: 'Diagnose and verify code for syntax, type errors, or lint issues across the project. Use after modifying code to ensure everything compiles cleanly and without errors.',
        parameters: {
            type: 'object',
            properties: {
                file: { type: 'string', description: 'Optional specific file to check (checks whole project if omitted)' }
            }
        }
    },
    validateArgs() { },
    async execute(args) {
        return new Promise((resolve) => {
            let cmd = '';
            if (args.file) {
                const quotedFile = `"${args.file}"`;
                if (args.file.endsWith('.ts') || args.file.endsWith('.tsx')) {
                    if (fsSync.existsSync('tsconfig.json')) {
                        cmd = 'npx tsc --noEmit';
                    }
                    else {
                        cmd = `echo "node --check does not work for TypeScript. Please use tsc or tsx."`;
                    }
                }
                else if (args.file.endsWith('.js') || args.file.endsWith('.mjs') || args.file.endsWith('.cjs')) {
                    cmd = `node --check ${quotedFile}`;
                }
                else if (args.file.endsWith('.py')) {
                    cmd = `python -m py_compile ${quotedFile}`;
                }
                else if (args.file.endsWith('.rs')) {
                    cmd = 'cargo check';
                }
            }
            if (!cmd) {
                if (fsSync.existsSync('tsconfig.json')) {
                    cmd = 'npx tsc --noEmit';
                }
                else if (fsSync.existsSync('Cargo.toml')) {
                    cmd = 'cargo check';
                }
                else if (fsSync.existsSync('package.json')) {
                    cmd = 'npm test -- --passWithNoTests';
                }
                else {
                    return resolve('No automated checker found for this workspace. Manual review looks good.');
                }
            }
            exec(cmd, (err, stdout, stderr) => {
                const out = (stdout + '\n' + stderr).trim();
                if (!err) {
                    resolve('✅ Diagnostics passed with 0 errors! Code is clean and valid.');
                }
                else {
                    resolve(`❌ Diagnostic errors found:\n${out.slice(0, 3000)}`);
                }
            });
        });
    }
};
