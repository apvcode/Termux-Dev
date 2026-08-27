import fs from 'fs/promises';
import fsSync from 'fs';
import path from 'path';
export class SnapshotManager {
    history = [];
    currentTurn = null;
    beginTurn() {
        this.currentTurn = {
            id: `turn_${Date.now()}`,
            timestamp: Date.now(),
            files: new Map()
        };
    }
    async recordFileBeforeChange(filePath) {
        if (!this.currentTurn) {
            this.beginTurn();
        }
        const resolved = path.resolve(filePath);
        if (this.currentTurn.files.has(resolved)) {
            return; // Already recorded original state for this turn
        }
        try {
            if (fsSync.existsSync(resolved)) {
                const content = await fs.readFile(resolved, 'utf8');
                this.currentTurn.files.set(resolved, {
                    filePath: resolved,
                    existed: true,
                    content
                });
            }
            else {
                this.currentTurn.files.set(resolved, {
                    filePath: resolved,
                    existed: false,
                    content: null
                });
            }
        }
        catch (err) {
            throw new Error(`Cannot safely snapshot file ${resolved} before edit: ${err.message}`);
        }
    }
    finishTurn() {
        if (this.currentTurn && this.currentTurn.files.size > 0) {
            this.history.push(this.currentTurn);
        }
        this.currentTurn = null;
    }
    async undoLastTurn() {
        const lastTurn = this.history.pop();
        if (!lastTurn || lastTurn.files.size === 0) {
            return { revertedFiles: [], count: 0 };
        }
        const revertedFiles = [];
        for (const [filePath, snap] of lastTurn.files.entries()) {
            try {
                const relPath = path.relative(process.cwd(), filePath) || filePath;
                if (snap.existed && snap.content !== null) {
                    const parentDir = path.dirname(filePath);
                    if (!fsSync.existsSync(parentDir)) {
                        await fs.mkdir(parentDir, { recursive: true });
                    }
                    await fs.writeFile(filePath, snap.content, 'utf8');
                    revertedFiles.push(relPath);
                }
                else {
                    // File was newly created in this turn, remove it
                    if (fsSync.existsSync(filePath)) {
                        await fs.unlink(filePath);
                        revertedFiles.push(`${relPath} (deleted)`);
                    }
                }
            }
            catch (err) {
                console.error(`Failed to revert ${filePath}: ${err.message}`);
            }
        }
        return { revertedFiles, count: revertedFiles.length };
    }
    getUndoCount() {
        return this.history.length;
    }
}
export const globalSnapshotManager = new SnapshotManager();
