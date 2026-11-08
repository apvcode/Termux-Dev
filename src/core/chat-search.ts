import fs from 'fs/promises';
import path from 'path';
import type { ChatSession } from './session.js';
import { SessionManager, sessionsDir } from './session.js';

/**
 * Search in the chats devx saved (`~/.devx/sessions`). Only what people said is indexed: the messages
 * of the user and the answers of the model. Tool output (file contents, command output) is left out:
 * it is huge, it is noise for a search, and it can be read with the chat when it is needed.
 */

const MAX_TEXT = 6000;

export interface IndexedMessage {
  /** Position in the saved chat (the system prompt is 0). */
  i: number;
  role: 'user' | 'assistant';
  text: string;
  /** The distinct lower-case words of the text. */
  words: string[];
}

export interface ChatIndexEntry {
  id: string;
  /** The short form of the id the user sees (`#47unz`). */
  short: string;
  title: string;
  cwd?: string;
  model: string;
  createdAt: number;
  updatedAt: number;
  messageCount: number;
  titleWords: string[];
  messages: IndexedMessage[];
}

export interface ChatHit {
  entry: ChatIndexEntry;
  score: number;
  snippets: Array<{ i: number; role: 'user' | 'assistant'; text: string }>;
}

const STOPWORDS = new Set((
  'the a an and or of to in on at is are was were be it this that for with as by from not do does did have has had i you we they he she me my our your ' +
  'как что это в на и не с по для из к у о за от до так вот или но же ли бы мы вы я он она они мой наш ваш там тут все всё был была было были'
).split(' '));

export function wordsOf(text: string): string[] {
  return Array.from(new Set((text.toLowerCase().match(/[\p{L}\p{N}]+/gu) ?? []).filter((w) => w.length >= 2)));
}

/** Words of a query: the meaningful ones (small filler words are dropped unless nothing else is left). */
export function queryWords(query: string): string[] {
  const all = wordsOf(query);
  const meaningful = all.filter((w) => !STOPWORDS.has(w));
  return (meaningful.length > 0 ? meaningful : all).slice(0, 8);
}

/**
 * How well a word of the text fits a word of the query: 2 the same, 1 the same start (other endings:
 * page/pages, прошлый/прошлом), 0 not at all.
 */
function strength(token: string, q: string): 0 | 1 | 2 {
  if (token === q) return 2;
  if (token.charCodeAt(0) !== q.charCodeAt(0)) return 0;
  const min = Math.min(token.length, q.length);
  if (min < 4) return 0;
  let k = 1;
  while (k < min && token[k] === q[k]) k++;
  return k >= Math.max(4, min - 2) ? 1 : 0;
}

interface MessageScore {
  score: number;
  /** How many of the query's words are in the text. */
  matched: number;
  /** The word of the text that matched first (to cut the snippet around it). */
  at?: string;
}

function scoreWords(words: string[], qs: string[]): MessageScore {
  let score = 0;
  let matched = 0;
  let at: string | undefined;
  for (const q of qs) {
    let best = 0;
    let bestWord: string | undefined;
    for (const w of words) {
      const s = strength(w, q);
      if (s > best) {
        best = s;
        bestWord = w;
        if (s === 2) break;
      }
    }
    if (best > 0) {
      score += best;
      matched++;
      at ??= bestWord;
    }
  }
  return { score, matched, at };
}

export function indexSession(session: ChatSession): ChatIndexEntry {
  const messages: IndexedMessage[] = [];
  session.messages.forEach((m, i) => {
    if (m.role !== 'user' && m.role !== 'assistant') return;
    const text = (m.content || '').trim().slice(0, MAX_TEXT);
    if (!text) return;
    messages.push({ i, role: m.role, text, words: wordsOf(text) });
  });
  return {
    id: session.id,
    short: session.id.split('_').pop() || session.id,
    title: session.title,
    cwd: session.cwd,
    model: session.model,
    createdAt: session.createdAt,
    updatedAt: session.updatedAt,
    messageCount: session.messages.filter((m) => m.role !== 'system').length,
    titleWords: wordsOf(session.title || ''),
    messages,
  };
}

const cache = new Map<string, { mtimeMs: number; size: number; entry: ChatIndexEntry | null }>();

/** All saved chats, the newest first. A chat that did not change since the last search is not read again. */
export async function loadChatIndex(): Promise<ChatIndexEntry[]> {
  const dir = sessionsDir();
  let files: string[];
  try {
    files = (await fs.readdir(dir)).filter((f) => f.endsWith('.json'));
  } catch {
    return [];
  }
  const seen = new Set<string>();
  const entries: ChatIndexEntry[] = [];
  for (const file of files) {
    const full = path.join(dir, file);
    seen.add(full);
    try {
      const st = await fs.stat(full);
      let hit = cache.get(full);
      if (!hit || hit.mtimeMs !== st.mtimeMs || hit.size !== st.size) {
        const parsed = JSON.parse(await fs.readFile(full, 'utf8')) as ChatSession;
        hit = { mtimeMs: st.mtimeMs, size: st.size, entry: parsed && parsed.id && Array.isArray(parsed.messages) ? indexSession(parsed) : null };
        cache.set(full, hit);
      }
      if (hit.entry && hit.entry.messageCount > 0) entries.push(hit.entry);
    } catch {}
  }
  for (const key of cache.keys()) if (!seen.has(key)) cache.delete(key);
  return entries.sort((a, b) => b.updatedAt - a.updatedAt);
}

export interface SearchOptions {
  limit?: number;
  /** Only chats that were held in this folder. */
  cwd?: string;
  /** A chat that is left out (the one that is open). */
  excludeId?: string;
}

function snippetAround(text: string, word: string | undefined): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  const pos = word ? flat.toLowerCase().indexOf(word) : -1;
  const start = Math.max(0, (pos < 0 ? 0 : pos) - 80);
  const end = Math.min(flat.length, start + 260);
  return `${start > 0 ? '…' : ''}${flat.slice(start, end)}${end < flat.length ? '…' : ''}`;
}

/** The chats that talk about the query, the best first; each with the messages that match best. */
export function searchEntries(entries: ChatIndexEntry[], query: string, opts: SearchOptions = {}): ChatHit[] {
  const qs = queryWords(query);
  if (qs.length === 0) return [];
  const need = Math.ceil(qs.length / 2);
  const limit = Math.min(10, Math.max(1, opts.limit ?? 5));
  const hits: ChatHit[] = [];

  for (const entry of entries) {
    if (entry.id === opts.excludeId) continue;
    if (opts.cwd && entry.cwd !== opts.cwd) continue;
    const title = scoreWords(entry.titleWords, qs);
    const scored = entry.messages
      .map((m) => ({ m, s: scoreWords(m.words, qs) }))
      .filter((x) => x.s.matched > 0)
      .sort((a, b) => b.s.score - a.s.score);
    const best = scored[0]?.s.matched ?? 0;
    if (best < need && title.matched < need) continue;
    const rest = scored.slice(1).reduce((n, x) => n + x.s.score, 0);
    const score = (scored[0]?.s.score ?? 0) * 3 + title.score * 2 + Math.min(rest, 10) * 0.2;
    hits.push({
      entry,
      score,
      snippets: scored
        .filter((x) => x.s.matched >= Math.min(need, best))
        .slice(0, 3)
        .sort((a, b) => a.m.i - b.m.i)
        .map((x) => ({ i: x.m.i, role: x.m.role, text: snippetAround(x.m.text, x.s.at) })),
    });
  }
  return hits.sort((a, b) => b.score - a.score || b.entry.updatedAt - a.entry.updatedAt).slice(0, limit);
}

/** The chat a reference points to: the full id, or the short form (`47unz`, `#47unz`). */
export function findEntry(entries: ChatIndexEntry[], ref: string): ChatIndexEntry | undefined {
  const r = ref.trim().replace(/^#/, '');
  return entries.find((e) => e.id === r || e.short === r);
}

export function activeChatId(): string {
  return SessionManager.activeId;
}

/** For tests: forget what was read. */
export function clearChatIndexCache(): void {
  cache.clear();
}
