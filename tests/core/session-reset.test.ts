import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { UsageTracker } from '../../src/core/usage.js';
import { SnapshotManager } from '../../src/core/snapshot.js';

describe('UsageTracker.reset', () => {
  it('zeroes the session counters but keeps the data saver limit', () => {
    const usage = UsageTracker.getInstance();
    usage.setLimit(50);
    usage.recordRequest(51_700);
    usage.recordResponseChunk(37_700);
    usage.recordTokens(9_600, 214, 0.0014);
    expect(usage.getSummary()).toMatchObject({ requestsCount: 1, totalTokens: 9_814, totalCost: 0.0014 });

    usage.reset();
    expect(usage.getSummary()).toMatchObject({
      requestsCount: 0,
      bytesSent: 0,
      bytesReceived: 0,
      totalBytes: 0,
      promptTokens: 0,
      completionTokens: 0,
      totalTokens: 0,
      totalCost: 0,
      dataSaverLimitMB: 50,
    });
    usage.setLimit(undefined);
  });
});

describe('SnapshotManager.reset', () => {
  let dir: string;
  beforeEach(async () => { dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-reset-')); });
  afterEach(async () => { await fs.rm(dir, { recursive: true, force: true }); });

  it('forgets earlier edits so a new session cannot undo them', async () => {
    const file = path.join(dir, 'a.txt');
    await fs.writeFile(file, 'original');
    const manager = new SnapshotManager();
    manager.beginTurn();
    await manager.recordFileBeforeChange(file);
    await fs.writeFile(file, 'edited');
    manager.finishTurn();
    expect(manager.getUndoCount()).toBe(1);

    manager.reset();
    expect(manager.getUndoCount()).toBe(0);
    expect(await manager.undoLastTurn()).toEqual({ revertedFiles: [], count: 0 });
    expect(await fs.readFile(file, 'utf8')).toBe('edited'); // untouched
  });

  it('also drops a turn that was still open', async () => {
    const file = path.join(dir, 'b.txt');
    await fs.writeFile(file, 'x');
    const manager = new SnapshotManager();
    manager.beginTurn();
    await manager.recordFileBeforeChange(file);
    manager.reset();
    manager.finishTurn();
    expect(manager.getUndoCount()).toBe(0);
  });
});
