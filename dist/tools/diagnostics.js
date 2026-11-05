import fsSync from 'fs';
import path from 'path';
import { runShell } from './bash.js';
const DIAGNOSE_TIMEOUT_SECONDS = 180;
// The file name ends up inside a quoted shell argument, so only plain path
// characters are accepted. Anything else (quotes, $, `, ;, &, |, <, >, %, ^,
// newlines, backslashes...) is rejected instead of being escaped.
const SAFE_PATH = /^[\w\-./@+ ]+$/;
/**
 * Validates the user/model supplied file and returns a cwd-relative POSIX path.
 * Throws if it is unsafe to place on a command line or escapes the project.
 */
export function resolveDiagnoseFile(file) {
    const cwd = process.cwd();
    const absolute = path.resolve(cwd, file);
    const rel = path.relative(cwd, absolute);
    if (!rel || rel === '..' || rel.startsWith('..' + path.sep) || path.isAbsolute(rel)) {
        throw new Error(`File must be inside the project directory: ${file}`);
    }
    const posix = rel.split(path.sep).join('/');
    if (posix.startsWith('-')) {
        throw new Error(`Invalid file name: ${file}`);
    }
    if (!SAFE_PATH.test(posix)) {
        throw new Error(`Invalid file name (unsupported characters): ${file}`);
    }
    if (!fsSync.existsSync(absolute)) {
        throw new Error(`File not found: ${file}`);
    }
    return posix;
}
function pickCommand(file) {
    if (file) {
        const quoted = `"${file}"`;
        if (file.endsWith('.ts') || file.endsWith('.tsx')) {
            if (fsSync.existsSync('tsconfig.json'))
                return 'npx --no-install tsc --noEmit';
            return 'echo "node --check does not work for TypeScript. Please use tsc or tsx."';
        }
        if (file.endsWith('.js') || file.endsWith('.mjs') || file.endsWith('.cjs'))
            return `node --check ${quoted}`;
        if (file.endsWith('.py'))
            return `python -m py_compile ${quoted}`;
        if (file.endsWith('.rs'))
            return 'cargo check';
    }
    if (fsSync.existsSync('tsconfig.json'))
        return 'npx --no-install tsc --noEmit';
    if (fsSync.existsSync('Cargo.toml'))
        return 'cargo check';
    if (fsSync.existsSync('package.json'))
        return 'npm test -- --passWithNoTests';
    return null;
}
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
    validateArgs(args) {
        if (args?.file !== undefined && args.file !== null && args.file !== '') {
            if (typeof args.file !== 'string')
                throw new Error('file must be a string');
            resolveDiagnoseFile(args.file);
        }
    },
    async execute(args, _config, context) {
        const file = args?.file ? resolveDiagnoseFile(args.file) : undefined;
        const cmd = pickCommand(file);
        if (!cmd) {
            return 'No automated checker found for this workspace. Manual review looks good.';
        }
        const { output, code, stopReason } = await runShell(cmd, {
            signal: context?.signal,
            timeoutSeconds: DIAGNOSE_TIMEOUT_SECONDS,
        });
        if (stopReason) {
            return `Diagnostics did not finish: ${stopReason}\n${output.trim().slice(-3000)}`;
        }
        if (code === 0) {
            return 'Diagnostics passed with 0 errors. Code is clean and valid.';
        }
        return `Diagnostic errors found:\n${output.trim().slice(-3000)}`;
    }
};
