export function reportRuntimeError(error, abort = null) {
    const details = error instanceof Error ? error.stack || error.message : String(error);
    console.error('\n[devx runtime error]:', details);
    process.exitCode = 1;
    abort?.();
}
