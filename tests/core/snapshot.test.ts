import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'fs/promises';
import path from 'path';
import os from 'os';
import { SnapshotManager } from '../../src/core/snapshot.js';

describe('SnapshotManager', () => {
  let tmpDir: string;
  let manager: SnapshotManager;

  beforeEach(async () => {
    tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-snap-test-'));
    manager = new SnapshotManager();
  });

  afterEach(async () => {
    await fs.rm(tmpDir, { recursive: true, force: true });
  });

  it('records file state before modification and reverts it on undo', async () => {
    const filePath = path.join(tmpDir, 'test.txt');
    await fs.writeFile(filePath, 'original content', 'utf8');

    manager.beginTurn();
    await manager.recordFileBeforeChange(filePath);

    // Modify file
    await fs.writeFile(filePath, 'modified content', 'utf8');
    manager.finishTurn();

    expect(manager.getUndoCount()).toBe(1);

    const undoRes = await manager.undoLastTurn();
    expect(undoRes.count).toBe(1);

    const restored = await fs.readFile(filePath, 'utf8');
    expect(restored).toBe('original content');
    expect(manager.getUndoCount()).toBe(0);
  });

  it('deletes newly created files on undo', async () => {
    const filePath = path.join(tmpDir, 'newfile.txt');

    manager.beginTurn();
    await manager.recordFileBeforeChange(filePath);

    // Create file
    await fs.writeFile(filePath, 'brand new content', 'utf8');
    manager.finishTurn();

    const undoRes = await manager.undoLastTurn();
    expect(undoRes.count).toBe(1);

    let exists = true;
    try {
      await fs.access(filePath);
    } catch {
      exists = false;
    }
    expect(exists).toBe(false);
  });
});
