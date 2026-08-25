import fs from 'fs/promises';
import fsSync from 'fs';
import path from 'path';
import os from 'os';
const SESSIONS_DIR = path.join(os.homedir(), '.devx', 'sessions');
export class SessionManager {
    currentSession;
    constructor(model, planMode) {
        this.currentSession = this.createEmptySession(model, planMode);
    }
    createEmptySession(model, planMode) {
        const now = Date.now();
        const id = `sess_${now}_${Math.random().toString(36).substring(2, 7)}`;
        return {
            id,
            title: 'New Session',
            createdAt: now,
            updatedAt: now,
            model,
            planMode,
            messages: [],
            totalCost: 0
        };
    }
    getSession() {
        return this.currentSession;
    }
    startNewSession(model, planMode) {
        this.currentSession = this.createEmptySession(model, planMode);
        return this.currentSession;
    }
    async save(messages, totalCost, model, planMode) {
        if (!fsSync.existsSync(SESSIONS_DIR)) {
            await fs.mkdir(SESSIONS_DIR, { recursive: true });
        }
        this.currentSession.messages = messages;
        this.currentSession.totalCost = totalCost;
        this.currentSession.updatedAt = Date.now();
        if (model)
            this.currentSession.model = model;
        if (planMode !== undefined)
            this.currentSession.planMode = planMode;
        if (this.currentSession.title === 'New Session') {
            const firstUserMsg = messages.find(m => m.role === 'user');
            if (firstUserMsg && firstUserMsg.content) {
                let title = firstUserMsg.content.trim().split('\n')[0];
                if (title.length > 50) {
                    title = title.substring(0, 47) + '...';
                }
                this.currentSession.title = title || 'Session';
            }
        }
        const filePath = path.join(SESSIONS_DIR, `${this.currentSession.id}.json`);
        await fs.writeFile(filePath, JSON.stringify(this.currentSession, null, 2), 'utf8');
    }
    static async listSessions() {
        if (!fsSync.existsSync(SESSIONS_DIR)) {
            return [];
        }
        try {
            const files = await fs.readdir(SESSIONS_DIR);
            const jsonFiles = files.filter(f => f.endsWith('.json'));
            const sessions = [];
            for (const file of jsonFiles) {
                try {
                    const content = await fs.readFile(path.join(SESSIONS_DIR, file), 'utf8');
                    const sess = JSON.parse(content);
                    if (sess.id && sess.messages) {
                        sessions.push(sess);
                    }
                }
                catch { }
            }
            sessions.sort((a, b) => b.updatedAt - a.updatedAt);
            return sessions;
        }
        catch {
            return [];
        }
    }
    static async loadSession(sessionId) {
        const filePath = path.join(SESSIONS_DIR, `${sessionId}.json`);
        if (!fsSync.existsSync(filePath))
            return null;
        try {
            const content = await fs.readFile(filePath, 'utf8');
            return JSON.parse(content);
        }
        catch {
            return null;
        }
    }
    static async deleteSession(sessionId) {
        const filePath = path.join(SESSIONS_DIR, `${sessionId}.json`);
        try {
            if (fsSync.existsSync(filePath)) {
                await fs.unlink(filePath);
                return true;
            }
            return false;
        }
        catch {
            return false;
        }
    }
    static async deleteAllSessions() {
        if (!fsSync.existsSync(SESSIONS_DIR))
            return 0;
        try {
            const files = await fs.readdir(SESSIONS_DIR);
            const jsonFiles = files.filter(f => f.endsWith('.json'));
            let count = 0;
            for (const file of jsonFiles) {
                try {
                    await fs.unlink(path.join(SESSIONS_DIR, file));
                    count++;
                }
                catch { }
            }
            return count;
        }
        catch {
            return 0;
        }
    }
    setLoadedSession(session) {
        this.currentSession = session;
    }
}
