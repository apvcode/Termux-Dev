import os from 'os';
import path from 'path';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    include: ['tests/**/*.test.ts'],
    // The prompt history is written to a file; the tests must not touch the real one in ~/.devx.
    env: { DEVX_HISTORY_FILE: path.join(os.tmpdir(), `devx-test-history-${process.pid}.jsonl`) },
  },
});
