import fs from 'fs/promises';
import fsSync from 'fs';
import path from 'path';
import os from 'os';
import { Message } from './types.js';
import { ensureSecureDir, writeSecureFile } from './secure-file.js';
import { MediaIndex, externalizeImages, imagesDirFor, internalizeImages, pruneImages } from './session-media.js';

export interface ChatSession {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  model: string;
  planMode: boolean;
  messages: Message[];
  /** The folder devx was started in (chats saved before this was recorded have none). */
  cwd?: string;
  totalCost: number;
  /** Part of totalCost is a catalogue estimate. */
  costEstimated?: boolean;
  /** The title was made by the model (not just the start of the first message). */
  titleGenerated?: boolean;
}

const SESSIONS_DIR = path.join(os.homedir(), '.devx', 'sessions');

/** Where the chats are saved. */
export function sessionsDir(): string {
  return SESSIONS_DIR;
}

export class SessionManager {
  /** The id of the chat that is open now (it is in the context already, so a search of earlier chats leaves it out). */
  static activeId = '';

  private currentSession: ChatSession;
  /** The files of the pictures already seen (see session-media.ts). */
  private media: MediaIndex = new Map();

  constructor(model: string, planMode: boolean) {
    this.currentSession = this.createEmptySession(model, planMode);
    SessionManager.activeId = this.currentSession.id;
  }

  private createEmptySession(model: string, planMode: boolean): ChatSession {
    const now = Date.now();
    const id = `sess_${now}_${ Math.random().toString(36).substring(2, 7) }`;
    return {
      id,
      title: 'New Session',
      createdAt: now,
      updatedAt: now,
      model,
      planMode,
      messages: [],
      cwd: process.cwd(),
      totalCost: 0
    };
  }

  /** Names the chat (after the model made a title) and keeps it from being renamed by the first message again. */
  public setTitle(title: string): void {
    this.currentSession.title = title;
    this.currentSession.titleGenerated = true;
  }

  public get hasGeneratedTitle(): boolean {
    return this.currentSession.titleGenerated === true;
  }

  public getSession(): ChatSession {
    return this.currentSession;
  }

  public startNewSession(model: string, planMode: boolean): ChatSession {
    this.currentSession = this.createEmptySession(model, planMode);
    SessionManager.activeId = this.currentSession.id;
    this.media = new Map();
    return this.currentSession;
  }

  public async save(messages: Message[], totalCost: number, model?: string, planMode?: boolean, costEstimated?: boolean): Promise<void> {
    // Transcripts contain file contents and pasted images: keep them private to the user.
    await ensureSecureDir(path.dirname(SESSIONS_DIR));
    await ensureSecureDir(SESSIONS_DIR);

    this.currentSession.messages = messages;
    this.currentSession.totalCost = totalCost;
    if (costEstimated !== undefined) this.currentSession.costEstimated = costEstimated;
    this.currentSession.updatedAt = Date.now();
    if (model) this.currentSession.model = model;
    if (planMode !== undefined) this.currentSession.planMode = planMode;

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

    // Pictures go into their own files; the chat file keeps only their names.
    const imagesDir = imagesDirFor(SESSIONS_DIR, this.currentSession.id);
    const stored = await externalizeImages(messages, imagesDir, this.media);

    const filePath = path.join(SESSIONS_DIR, `${this.currentSession.id}.json`);
    await writeSecureFile(filePath, JSON.stringify({ ...this.currentSession, messages: stored.messages }, null, 2));
    await pruneImages(imagesDir, stored.used);
  }

  public static async listSessions(): Promise<ChatSession[]> {
    if (!fsSync.existsSync(SESSIONS_DIR)) {
      return [];
    }

    try {
      const files = await fs.readdir(SESSIONS_DIR);
      const jsonFiles = files.filter(f => f.endsWith('.json'));
      const sessions: ChatSession[] = [];

      for (const file of jsonFiles) {
        try {
          const content = await fs.readFile(path.join(SESSIONS_DIR, file), 'utf8');
          const sess = JSON.parse(content);
          if (sess.id && sess.messages) {
            sessions.push(sess);
          }
        } catch {}
      }

      sessions.sort((a, b) => b.updatedAt - a.updatedAt);
      return sessions;
    } catch {
      return [];
    }
  }

  public static async loadSession(sessionId: string): Promise<ChatSession | null> {
    const filePath = path.join(SESSIONS_DIR, `${sessionId}.json`);
    if (!fsSync.existsSync(filePath)) return null;

    try {
      const content = await fs.readFile(filePath, 'utf8');
      const session = JSON.parse(content) as ChatSession;
      session.messages = await internalizeImages(session.messages ?? [], imagesDirFor(SESSIONS_DIR, sessionId));
      return session;
    } catch {
      return null;
    }
  }

  public static async deleteSession(sessionId: string): Promise<boolean> {
    const filePath = path.join(SESSIONS_DIR, `${sessionId}.json`);
    try {
      await fs.rm(imagesDirFor(SESSIONS_DIR, sessionId), { recursive: true, force: true }).catch(() => {});
      if (fsSync.existsSync(filePath)) {
        await fs.unlink(filePath);
        return true;
      }
      return false;
    } catch {
      return false;
    }
  }

  public static async deleteAllSessions(): Promise<number> {
    if (!fsSync.existsSync(SESSIONS_DIR)) return 0;
    try {
      const files = await fs.readdir(SESSIONS_DIR);
      const jsonFiles = files.filter(f => f.endsWith('.json'));
      let count = 0;
      for (const file of jsonFiles) {
        try {
          await fs.unlink(path.join(SESSIONS_DIR, file));
          count++;
        } catch {}
      }
      // The pictures of the chats, and those of chats whose file is already gone.
      for (const name of files.filter(f => f.endsWith('.images'))) {
        await fs.rm(path.join(SESSIONS_DIR, name), { recursive: true, force: true }).catch(() => {});
      }
      return count;
    } catch {
      return 0;
    }
  }

  public setLoadedSession(session: ChatSession) {
    this.currentSession = session;
    SessionManager.activeId = session.id;
  }
}
