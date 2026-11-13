import fs from 'fs/promises';
import path from 'path';
import crypto from 'crypto';
import { Agent, getActionDescription } from '../core/loop.js';
import { History } from '../core/history.js';
import { SessionManager, ChatSession } from '../core/session.js';
import { AgentConfig, LLMProvider, Message, TokenUsage, Tool, ToolSource } from '../core/types.js';
import { buildSystemPrompt } from '../prompts/builder.js';
import { createProvider } from '../providers/index.js';
import { getToolSource, lastPlanReady, resetPlanReady, getTools } from '../tools/index.js';
import { CLIConsoleGuard, buildPermissionRequest, newPermissionMemory } from '../permissions/guard.js';
import { CostLedger } from '../core/cost.js';
import { UsageTracker } from '../core/usage.js';
import { contextBreakdown } from '../core/context-stats.js';
import { getModelContextInfo } from '../core/models.js';
import { refreshFormats } from '../core/formats.js';
import { refreshCapabilities } from '../core/capabilities.js';
import { MCPManager, readClaudeServers, saveUserServers } from '../mcp/manager.js';
import { getBackgroundManager } from '../core/background.js';
import { globalSnapshotManager } from '../core/snapshot.js';
import { Checkpoint, createCheckpoint, diffCheckpoint, restoreCheckpoint } from '../core/checkpoint.js';
import { generateChatTitle, provisionalTitle } from '../core/title.js';
import { savePlanFile } from '../core/planfile.js';
import { effectiveReasoning, isLevel, withEffort } from '../core/reasoning.js';
import { modelAcceptsImages } from '../core/vision.js';
import { CustomCommandManager } from '../core/commands.js';
import { MemoryManager } from '../core/memory.js';
import { interaction } from '../core/interaction.js';
import { redactSecrets } from '../core/redact.js';
import { COMPACT_MARK } from '../cli/transcript.js';
import { forProvider, loadRemoteConfig, patchGlobalConfig } from './config.js';

/** What a session tells the phone, in order. `e` is the kind; everything else is its payload. */
export type RtEvent = { e: string; [key: string]: any };

export interface Attachment {
  name: string;
  mime: string;
  /** Where the host stored the file. */
  path: string;
}

export interface RuntimeOptions {
  cwd: string;
  /** Resume this saved chat; a new one is started when missing. */
  sessionId?: string;
  planMode?: boolean;
  emit: (event: RtEvent) => void;
  /** Asks the host something (the phone's memory). */
  rpc: (method: string, params: any) => Promise<any>;
  /** For tests: a fake model. */
  providerFactory?: (config: any, light?: boolean) => LLMProvider;
}

const TOOL_RESULT_CAP = 6000;
const TOOL_ARGS_CAP = 1500;
const MAX_IMAGE_BYTES = 20 * 1024 * 1024;
const MAX_TEXT_FILE = 200 * 1024;

const clip = (text: string, max: number) => (text.length > max ? `${text.slice(0, max)}\n… (${text.length - max} more characters)` : text);

/** One conversation, running in its own process and its own folder; what the phone sees of it. */
export class SessionRuntime {
  private opts: RuntimeOptions;
  private config: any = {};
  private trusted = false;
  private mcpPending = 0;
  private history = new History();
  private sessions!: SessionManager;
  private cost = new CostLedger();
  private planMode: boolean;
  private mcpReady: Promise<void> = Promise.resolve();
  private permissionMemory = newPermissionMemory();
  private checkpoints: Checkpoint[] = [];
  private abort: AbortController | null = null;
  private running = false;
  private pending: Message[] = [];
  private titleAttempts = 0;
  private pendingReasoning: { text: string; sec: string; stats: string } | null = null;
  private lastCtxUsed = 0;
  private turnStartedAt = 0;
  private profile: { name?: string; memory?: boolean } = {};
  private seen = new Set<string>();
  private closed = false;

  /** What is happening right now, for a phone that joins in the middle of a turn. */
  private live = {
    text: '',
    reasoning: '',
    tools: new Map<string, RtEvent>(),
    perm: null as RtEvent | null,
    ask: null as RtEvent | null,
    plan: null as RtEvent | null,
  };
  private permWaiters = new Map<string, (answer: string) => void>();
  private askWaiters = new Map<string, (answers: Array<string | null>) => void>();
  private planWaiters = new Map<string, (choice: string) => void>();

  constructor(opts: RuntimeOptions) {
    this.opts = opts;
    this.planMode = !!opts.planMode;
  }

  // ───────────────────────── start ─────────────────────────

  async init(): Promise<void> {
    const loaded = await loadRemoteConfig(this.opts.cwd);
    this.config = loaded.config;
    this.trusted = loaded.trusted;
    this.mcpPending = loaded.mcpPending;
    MemoryManager.enabled = this.config.enableMemory !== false;
    this.sessions = new SessionManager(this.config.model, this.planMode);
    this.sessions.getSession().cwd = this.opts.cwd;

    if (this.opts.sessionId) {
      const saved = await SessionManager.loadSession(this.opts.sessionId);
      if (!saved) throw new Error('That chat no longer exists.');
      this.adopt(saved);
    } else {
      this.history = new History();
      this.history.addMessage({ role: 'system', content: await this.systemPrompt() });
    }
    this.installInteraction();
    void this.refreshEndpoint().then(() => this.emitState());
    // (Tests use a fake model and must not start the MCP servers of whoever runs them.)
    if (!this.opts.providerFactory) this.mcpReady = MCPManager.getInstance()
      .init(undefined, this.config.projectMcpTrusted === true)
      .then(async () => { this.history.updateSystemPrompt(await this.systemPrompt()); })
      .catch(() => undefined);
  }

  private adopt(saved: ChatSession): void {
    this.sessions.setLoadedSession(saved);
    this.history = new History();
    for (const m of saved.messages) this.history.addMessage(m);
    if (!this.opts.providerFactory) {
      MCPManager.getInstance().resetLoaded();
      MCPManager.getInstance().restoreLoaded(saved.messages);
    }
    if (saved.model) {
      this.config = { ...this.config, model: saved.model };
      this.config.maxContextTokens = getModelContextInfo(saved.model).limit;
    }
    if (saved.planMode !== undefined) this.planMode = saved.planMode;
    this.cost.restore(saved.totalCost || 0, saved.costEstimated === true);
    UsageTracker.getInstance().restoreCost(saved.totalCost || 0, saved.costEstimated === true);
    if (saved.title && saved.title !== 'New Session') this.titleAttempts = 2;
    // The system prompt of a saved chat is from then: it is written again for today's folder and mode.
    void this.systemPrompt().then((p) => this.history.updateSystemPrompt(p));
  }

  private async systemPrompt(): Promise<string> {
    let prompt = await buildSystemPrompt(this.planMode);
    if (this.profile.name) {
      prompt += `\n\nTHE PERSON\nYou are talking with ${this.profile.name} through the devx app on their phone. Address them by name only when it is natural.`;
      if (this.profile.memory) {
        prompt += ` Their phone keeps notes about them (preferences, facts they asked to remember). When you need something personal that you do not know, look it up with the 'phone_memory' tool instead of asking again; never repeat what you find there beyond what the task needs.`;
      }
    }
    return prompt;
  }

  private installInteraction(): void {
    interaction.askQuestions = (questions) => new Promise((resolve) => {
      const id = crypto.randomBytes(4).toString('hex');
      const ev: RtEvent = { e: 'ask', id, questions };
      this.live.ask = ev;
      this.askWaiters.set(id, (answers) => {
        this.live.ask = null;
        this.emit({ e: 'ask_done', id });
        resolve(questions.map((q, i) => ({ question: q.question, answer: answers[i] ?? undefined })));
      });
      this.emit(ev);
    });
  }

  // ───────────────────────── what the phone may change ─────────────────────────

  setProfile(profile: { name?: string; memory?: boolean }): void {
    const changed = profile.name !== this.profile.name || !!profile.memory !== !!this.profile.memory;
    this.profile = { name: profile.name?.slice(0, 60), memory: !!profile.memory };
    if (changed) void this.systemPrompt().then((p) => this.history.updateSystemPrompt(p));
  }

  async setMode(opts: { plan?: boolean; yolo?: boolean; extra?: boolean }): Promise<void> {
    if (opts.plan !== undefined && opts.plan !== this.planMode) {
      this.planMode = opts.plan;
      this.history.updateSystemPrompt(await this.systemPrompt());
    }
    if (opts.extra !== undefined) {
      this.config.autoApproveAll = opts.extra;
      if (opts.extra) this.config.autoApprove = true;
    }
    if (opts.yolo !== undefined) {
      this.config.autoApprove = opts.yolo;
      if (!opts.yolo) this.config.autoApproveAll = false;
    }
    this.emitState();
  }

  /**
   * Asks the endpoint what the model can do (reasoning levels, price) and which wire formats it speaks, as the terminal does.
   * Without it a gateway model shows fewer levels and its Claude models would go out in the wrong format.
   */
  private async refreshEndpoint(): Promise<void> {
    if (this.opts.providerFactory) return;
    await Promise.all([refreshCapabilities(this.config), refreshFormats(this.config)]).catch(() => null);
  }

  /** Another model (and perhaps another provider) for this chat. */
  async setModel(providerId: string | undefined, model: string): Promise<void> {
    const provider = providerId || this.config.provider;
    this.config = forProvider(this.config, provider, model);
    this.sessions.getSession().model = model;
    void this.refreshEndpoint().then(() => this.emitState());
    this.emitState();
  }

  setEffort(level: string | null): { ok: boolean; why?: string } {
    const { caps } = effectiveReasoning({ ...this.config, effortOverride: undefined });
    if (level === null || level === 'auto') {
      this.config.effortOverride = '';
    } else if (!caps.supported) {
      return { ok: false, why: `${this.config.model} has no reasoning setting.` };
    } else if (!isLevel(level) || !caps.levels.includes(level)) {
      return { ok: false, why: `${this.config.model} offers: ${caps.levels.join(', ')}.` };
    } else {
      this.config.effortOverride = level;
      void patchGlobalConfig((c) => { c.effort = withEffort(c.effort, this.config.model, level); }).catch(() => null);
    }
    this.emitState();
    return { ok: true };
  }

  async trustFolder(): Promise<void> {
    await patchGlobalConfig((c) => { c.trustedProjects = { ...(c.trustedProjects || {}), [this.opts.cwd]: true }; });
    const loaded = await loadRemoteConfig(this.opts.cwd);
    this.config = { ...loaded.config, model: this.config.model, provider: this.config.provider, baseUrl: this.config.baseUrl, apiKey: this.config.apiKey, maxContextTokens: this.config.maxContextTokens };
    this.trusted = loaded.trusted;
    this.mcpPending = loaded.mcpPending;
    this.emitState();
  }

  // ───────────────────────── events ─────────────────────────

  private emit(event: RtEvent): void {
    if (this.closed) return;
    this.opts.emit(event);
  }

  state(): RtEvent {
    const { caps, level } = effectiveReasoning(this.config);
    const session = this.sessions.getSession();
    return {
      e: 'state',
      id: session.id,
      title: session.title,
      cwd: this.opts.cwd,
      running: this.running,
      plan: this.planMode,
      yolo: !!this.config.autoApprove,
      extra: !!this.config.autoApprove && !!this.config.autoApproveAll,
      model: this.config.model,
      provider: this.config.provider,
      effort: { supported: caps.supported, levels: caps.levels, level: level ?? null, alwaysOn: caps.alwaysOn },
      cost: this.cost.total,
      costEstimated: this.cost.estimated,
      ctx: { used: this.lastCtxUsed, limit: this.config.maxContextTokens || 100000 },
      trusted: this.trusted,
      mcpPending: this.mcpPending,
      queued: this.pending.length,
    };
  }

  private emitState(): void {
    this.emit(this.state());
  }

  /** The conversation so far, shaped for the phone, with what is going on right now. */
  snapshot(limit = 120): RtEvent {
    const items = snapshotItems(this.history.getMessages());
    const ordered = items;
    const more = Math.max(0, ordered.length - limit);
    return {
      e: 'snapshot',
      state: this.state(),
      items: ordered.slice(-limit),
      more,
      live: this.running ? {
        text: this.live.text,
        reasoning: clip(this.live.reasoning, 20000),
        tools: [...this.live.tools.values()],
        perm: this.live.perm,
        ask: this.live.ask,
        plan: this.live.plan,
        startedAt: this.turnStartedAt,
      } : null,
    };
  }

  // ───────────────────────── a turn ─────────────────────────

  /** A message from the phone: starts a turn, or joins the one that is running. */
  async send(text: string, attachments: Attachment[] = []): Promise<{ queued: boolean }> {
    const message = await this.toMessage(text, attachments);
    if (this.running) {
      this.pending.push(message);
      this.emit({ e: 'queued', n: this.pending.length, text: text.slice(0, 200) });
      this.emitState();
      return { queued: true };
    }
    void this.turn(message);
    return { queued: false };
  }

  private async toMessage(text: string, attachments: Attachment[]): Promise<Message> {
    const images: NonNullable<Message['images']> = [];
    const blocks: string[] = [];
    const vision = modelAcceptsImages(this.config.model) !== false;
    for (const a of attachments) {
      try {
        const stat = await fs.stat(a.path);
        if (a.mime.startsWith('image/') && vision && stat.size <= MAX_IMAGE_BYTES) {
          const data = await fs.readFile(a.path);
          images.push({ path: a.path, dataUrl: `data:${a.mime};base64,${data.toString('base64')}` });
          continue;
        }
        if (stat.size <= MAX_TEXT_FILE && isTextual(a.mime, a.name)) {
          blocks.push(`[Attached file: ${a.path}]\n\`\`\`\n${await fs.readFile(a.path, 'utf8')}\n\`\`\``);
          continue;
        }
        blocks.push(`[Attached file saved at: ${a.path} (${a.mime}, ${stat.size} bytes). Open it with your tools if you need it.]`);
      } catch {
        blocks.push(`[Attachment ${a.name} could not be read]`);
      }
    }
    const content = blocks.length > 0 ? `${text}\n\n--- Attached Files ---\n${blocks.join('\n\n')}` : text;
    return { role: 'user', content, images: images.length > 0 ? images : undefined, at: Date.now() };
  }

  private makeProvider(light = false): LLMProvider {
    return this.opts.providerFactory ? this.opts.providerFactory(this.config, light) : createProvider(this.config, { light });
  }

  private sideProvider(): LLMProvider {
    const inner = this.makeProvider(true);
    return {
      id: inner.id,
      chat: async (request) => {
        const res = await inner.chat(request);
        if (res.usage) this.cost.add(res.usage);
        return res;
      },
    };
  }

  private tools(): ToolSource {
    const base = this.opts.providerFactory ? { current: () => getTools(this.planMode) as Tool[] } : getToolSource(this.planMode);
    const memoryTool: Tool = {
      name: 'phone_memory',
      definition: {
        name: 'phone_memory',
        description: "Reads the notes the user's phone keeps about them (what they asked to remember: preferences, facts, contacts). Use it when you need personal information you do not have. action 'list' returns all notes, 'search' the notes that match the query. Fails when the phone is not connected.",
        parameters: {
          type: 'object',
          properties: {
            action: { type: 'string', enum: ['list', 'search'], description: 'list or search' },
            query: { type: 'string', description: 'Words to look for (for search)' },
          },
          required: ['action'],
        },
      },
      validateArgs(args: any) { if (!args || !['list', 'search'].includes(args.action)) throw new Error("action must be 'list' or 'search'"); },
      execute: async (args: any) => {
        try {
          const res = await this.opts.rpc('phone_memory', { action: args.action, query: args.query ?? '' });
          return typeof res === 'string' ? res : JSON.stringify(res);
        } catch (err: any) {
          return `The phone did not answer: ${err.message}`;
        }
      },
    };
    return {
      current: () => [...base.current(), ...(this.profile.memory ? [memoryTool] : [])],
      explainMissing: (name) => ('explainMissing' in base ? (base as ToolSource).explainMissing?.(name) : undefined),
    };
  }

  private async turn(first: Message): Promise<void> {
    this.running = true;
    this.turnStartedAt = Date.now();
    this.live = { text: '', reasoning: '', tools: new Map(), perm: null, ask: null, plan: null };
    const abort = (this.abort = new AbortController());
    const costAtStart = this.cost.total;
    let toolCount = 0;
    let tokensOut = 0;
    let tokensIn = 0;
    let failed: string | null = null;

    this.emit({ e: 'turn_start', at: this.turnStartedAt });
    this.emit({ e: 'user', text: first.content.split('\n\n--- Attached Files ---')[0], files: attachedNames(first.content), images: first.images?.length ?? 0 });
    this.emitState();

    globalSnapshotManager.beginTurn();
    let checkpoint: Checkpoint | null = null;
    try {
      if (!this.planMode && !this.opts.providerFactory) {
        checkpoint = await createCheckpoint(this.opts.cwd, this.checkpoints[this.checkpoints.length - 1]?.sha);
        if (checkpoint) this.checkpoints.push(checkpoint);
      }
      this.history.addMessage({ ...first, checkpoint: checkpoint?.sha });
      if (this.sessions.getSession().title === 'New Session' && !this.sessions.hasGeneratedTitle) {
        this.sessions.getSession().title = provisionalTitle(first.content.split('\n\n--- Attached Files ---')[0]);
        this.emit({ e: 'title', title: this.sessions.getSession().title });
      }
      await this.refreshEndpoint();
      await this.mcpReady;

      const guard = new CLIConsoleGuard(this.config.autoApprove, [...(this.config.bashAllowlist || [])], {
        extra: !!this.config.autoApproveAll,
        remote: true,
        memory: this.permissionMemory,
        onAlways: (r) => {
          void patchGlobalConfig((c) => {
            if (r.kind === 'bash') c.bashAllowlist = Array.from(new Set([...(c.bashAllowlist || []), r.key]));
            else c.allowedTools = Array.from(new Set([...(c.allowedTools || []), r.key]));
          }).catch(() => null);
        },
        decide: (request, offer, danger) => this.askPermission(request, offer, danger, abort.signal),
      });
      const agentConfig: AgentConfig = {
        maxContextTokens: this.config.maxContextTokens || 100000,
        maxIterations: this.config.maxIterations || 100,
        autoApprove: this.config.autoApprove,
        bashAllowlist: this.config.bashAllowlist,
        hooks: this.config.hooks,
      };
      const bg = getBackgroundManager();
      const agent = new Agent(agentConfig, this.makeProvider(), this.tools(), this.history, guard);
      let reasoningStart = 0;

      for await (const event of agent.run(abort.signal, {
        takePending: () => this.pending.splice(0),
        hasPending: () => this.pending.length > 0,
        takeNotes: () => bg.takeNotes(),
        hasNotes: () => bg.hasNotes(),
      })) {
        switch (event.type) {
          case 'reasoning_delta':
            if (!reasoningStart) reasoningStart = Date.now();
            this.live.reasoning += event.delta;
            this.emit({ e: 'reasoning', d: event.delta });
            break;
          case 'text_delta':
            this.live.text += event.delta;
            this.emit({ e: 'text', d: event.delta });
            break;
          case 'text':
            this.live.text += event.content;
            this.emit({ e: 'text', d: event.content });
            break;
          case 'tool_generating':
            this.emit({ e: 'tool_gen', name: event.name, bytes: event.bytes, hint: event.targetHint });
            break;
          case 'tool_start': {
            toolCount++;
            // What was said before a tool is a finished paragraph: the next text starts a new one.
            this.endSegment(reasoningStart);
            reasoningStart = 0;
            const ev: RtEvent = { e: 'tool_start', id: event.id, name: event.name, desc: event.actionDesc, args: clip(event.argsRaw, TOOL_ARGS_CAP) };
            this.live.tools.set(event.id, ev);
            this.emit(ev);
            break;
          }
          case 'tool_end':
            this.live.tools.delete(event.id);
            this.emit({ e: 'tool_end', id: event.id, name: event.name, result: clip(event.result, TOOL_RESULT_CAP), size: event.result.length });
            break;
          case 'usage':
            this.onUsage(event.usage);
            tokensOut += event.usage.completionTokens || 0;
            tokensIn += event.usage.promptTokens || 0;
            this.attachReasoning(reasoningStart);
            reasoningStart = 0;
            break;
          case 'user_message':
            this.emit({ e: 'queued_taken', text: event.content.split('\n\n--- Attached Files ---')[0].slice(0, 400) });
            break;
          case 'background_note':
            this.emit({ e: 'note', text: event.content });
            break;
          case 'reconnecting':
            this.emit({ e: 'reconnecting', attempt: event.attempt, max: event.maxAttempts, reason: event.reason });
            break;
          case 'reconnected':
            this.emit({ e: 'reconnected' });
            break;
          case 'system':
            this.emit({ e: 'system', text: event.message });
            break;
          case 'error':
            failed = event.message;
            this.emit({ e: 'error', text: event.message, fatal: event.isFatal });
            break;
        }
      }
    } catch (err: any) {
      if (!abort.signal.aborted) {
        failed = err?.message ?? String(err);
        this.emit({ e: 'error', text: failed, fatal: true });
      }
    } finally {
      this.endSegment(0);
      const aborted = abort.signal.aborted;
      this.abort = null;
      this.running = false;
      globalSnapshotManager.finishTurn();
      // Nobody is left to answer: the open questions end here.
      this.resolveOpen();
      // Saved before the phone is told the turn is over: a list of chats asked for right then must show it.
      await this.save();
      this.emit({ e: 'turn_end', ms: Date.now() - this.turnStartedAt, cost: this.cost.total - costAtStart, tools: toolCount, tokensOut, tokensIn, aborted, failed });
      this.emitState();
    }

    void this.nameChat();
    // A message typed while the model was finishing (or after an abort) is answered next.
    if (!abort.signal.aborted && this.pending.length > 0 && !this.closed) {
      const next = this.pending.shift()!;
      void this.turn(next);
      return;
    }
    if (!abort.signal.aborted && this.planMode) await this.planCheck();
  }

  /** The reasoning goes into the saved chat with the reply it came before. */
  private attachReasoning(startedAt: number): void {
    const text = this.live.reasoning;
    if (!text) return;
    const sec = startedAt ? String(Math.max(1, Math.round((Date.now() - startedAt) / 1000))) : '1';
    const messages = this.history.getMessages();
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i].role === 'assistant') {
        if (!messages[i].reasoning) messages[i].reasoning = { text, sec, stats: '' };
        break;
      }
      if (messages[i].role === 'user') break;
    }
    this.live.reasoning = '';
  }

  private endSegment(_reasoningStart: number): void {
    // The live text of a finished paragraph is no longer "live": it is in the history already.
    this.live.text = '';
  }

  private onUsage(usage: TokenUsage): void {
    this.cost.add(usage);
    this.lastCtxUsed = usage.promptTokens || this.lastCtxUsed;
    this.emit({ e: 'usage', prompt: usage.promptTokens, completion: usage.completionTokens, cost: usage.cost ?? null, total: this.cost.total, estimated: this.cost.estimated });
    this.emitState();
  }

  private askPermission(request: ReturnType<typeof buildPermissionRequest>, offer: { session?: boolean; always?: boolean }, danger: boolean, signal: AbortSignal): Promise<'allow' | 'session' | 'always' | 'deny'> {
    return new Promise((resolve) => {
      const id = crypto.randomBytes(4).toString('hex');
      const ev: RtEvent = { e: 'perm', id, title: request.title, lines: request.lines.slice(0, 40), notes: request.notes ?? [], danger, offer };
      this.live.perm = ev;
      const done = (answer: string) => {
        this.permWaiters.delete(id);
        this.live.perm = null;
        this.emit({ e: 'perm_done', id, answer });
        resolve(answer === 'allow' || answer === 'session' || answer === 'always' ? (answer as any) : 'deny');
      };
      this.permWaiters.set(id, done);
      signal.addEventListener('abort', () => { if (this.permWaiters.has(id)) done('deny'); }, { once: true });
      this.emit(ev);
    });
  }

  answerPermission(id: string, answer: string): boolean {
    const w = this.permWaiters.get(id);
    if (!w) return false;
    w(answer);
    return true;
  }

  answerQuestions(id: string, answers: Array<string | null>): boolean {
    const w = this.askWaiters.get(id);
    if (!w) return false;
    w(answers);
    return true;
  }

  private resolveOpen(): void {
    for (const w of [...this.permWaiters.values()]) w('deny');
    for (const w of [...this.askWaiters.values()]) w([]);
  }

  /** In plan mode, a finished plan waits for the person: Go (work starts) or change it. */
  private async planCheck(): Promise<void> {
    const last = this.history.getMessages().slice(-1)[0];
    const hasTool = lastPlanReady !== null;
    const text = last && last.role === 'assistant' ? last.content : '';
    const hasText = /Цель и архитектурный обзор|Goal and architecture|Step-by-step changes|Пошаговый список изменений|FINAL PLAN|План готов|Архитектурный план|план готов/.test(text);
    if (!hasTool && !hasText) return;
    const approved = lastPlanReady ? { summary: lastPlanReady.summary, files: lastPlanReady.files, steps: lastPlanReady.steps } : text;
    resetPlanReady();
    const id = crypto.randomBytes(4).toString('hex');
    const ev: RtEvent = { e: 'plan', id, summary: typeof approved === 'string' ? approved : approved.summary, files: typeof approved === 'string' ? [] : approved.files ?? [], steps: typeof approved === 'string' ? [] : approved.steps ?? [] };
    this.live.plan = ev;
    const choice = await new Promise<string>((resolve) => {
      this.planWaiters.set(id, resolve);
      this.emit(ev);
    });
    this.planWaiters.delete(id);
    this.live.plan = null;
    this.emit({ e: 'plan_done', id, choice });
    if (choice === 'go') {
      this.planMode = false;
      await savePlanFile(this.opts.cwd, approved as any).catch(() => null);
      this.history.updateSystemPrompt(await this.systemPrompt());
      this.emitState();
      await this.send('Go!');
    }
  }

  decidePlan(id: string, choice: string): boolean {
    const w = this.planWaiters.get(id);
    if (!w) return false;
    w(choice === 'go' ? 'go' : 'edit');
    return true;
  }

  stop(): void {
    this.abort?.abort();
    this.pending.length = 0;
    this.resolveOpen();
  }

  // ───────────────────────── saving and naming ─────────────────────────

  private async save(): Promise<void> {
    try {
      await this.sessions.save(this.history.getMessages(), this.cost.total, this.config.model, this.planMode, this.cost.estimated);
    } catch (err: any) {
      this.emit({ e: 'system', text: `Could not save the chat: ${err?.message ?? err}` });
    }
  }

  private async nameChat(): Promise<void> {
    if (this.sessions.hasGeneratedTitle || this.titleAttempts >= 2 || this.opts.providerFactory) return;
    this.titleAttempts++;
    const msgs = this.history.getMessages();
    const firstUser = msgs.find((m) => m.role === 'user')?.content ?? '';
    const reply = [...msgs].reverse().find((m) => m.role === 'assistant' && m.content?.trim())?.content ?? '';
    if (!firstUser.trim() || !reply.trim()) return;
    const forSession = this.sessions.getSession().id;
    try {
      const title = await generateChatTitle(this.sideProvider(), firstUser.split('\n\n--- Attached Files ---')[0], reply);
      if (!title || this.sessions.getSession().id !== forSession) return;
      this.sessions.setTitle(title);
      await this.save();
      this.emit({ e: 'title', title });
      this.emitState();
    } catch {}
  }

  async rename(title: string): Promise<void> {
    const clean = title.replace(/\s+/g, ' ').trim().slice(0, 80);
    if (!clean) return;
    this.sessions.setTitle(clean);
    await this.save();
    this.emit({ e: 'title', title: clean });
    this.emitState();
  }

  // ───────────────────────── commands ─────────────────────────

  /** A new, empty chat in the same folder; the old one stays saved. */
  async reset(): Promise<void> {
    this.stop();
    const bg = getBackgroundManager();
    await bg.stopAll().catch(() => 0);
    bg.clearFinished();
    bg.takeNotes();
    this.sessions.startNewSession(this.config.model, this.planMode).cwd = this.opts.cwd;
    this.checkpoints.length = 0;
    this.titleAttempts = 0;
    this.history = new History();
    MCPManager.getInstance().resetLoaded();
    this.history.addMessage({ role: 'system', content: await this.systemPrompt() });
    this.cost.reset();
    this.lastCtxUsed = 0;
    UsageTracker.getInstance().reset();
    globalSnapshotManager.reset();
    this.live = { text: '', reasoning: '', tools: new Map(), perm: null, ask: null, plan: null };
    this.emit({ e: 'reset', id: this.sessions.getSession().id });
    this.emitState();
  }

  async compact(): Promise<{ ok: boolean; text: string }> {
    if (this.running) return { ok: false, text: 'Wait until the answer is finished, or stop it first.' };
    const msgs = this.history.getMessages();
    if (msgs.length <= 2) return { ok: false, text: 'The conversation is already short.' };
    try {
      await this.refreshEndpoint();
      const provider = this.sideProvider();
      const forSummary = new History();
      for (const m of msgs) forSummary.addMessage({ ...m, images: undefined });
      forSummary.pruneToLimit(Math.floor((this.config.maxContextTokens || 100000) * 0.7));
      const res = await provider.chat({
        messages: [
          ...forSummary.getMessages(),
          { role: 'user', content: 'Write a short, structured summary of our conversation so far: what the user wants, decisions made, files created or changed, and what is left to do. Plain text, no filler.' },
        ],
      });
      if (!res.content?.trim()) throw new Error('the model returned an empty summary');
      this.history.clear();
      this.history.addMessage({ role: 'system', content: await this.systemPrompt() });
      this.history.addMessage({ role: 'user', content: `${COMPACT_MARK} Summary of our conversation so far:\n${res.content}` });
      this.lastCtxUsed = 0;
      await this.save();
      this.emit({ e: 'compact', summary: res.content });
      this.emitState();
      return { ok: true, text: 'Context compacted.' };
    } catch (err: any) {
      return { ok: false, text: `Compaction failed: ${err.message}` };
    }
  }

  async undo(): Promise<{ ok: boolean; text: string }> {
    if (this.running) return { ok: false, text: 'Stop the answer first.' };
    const cp = this.checkpoints[this.checkpoints.length - 1];
    const diff = cp ? await diffCheckpoint(cp).catch(() => null) : null;
    if (cp && diff && diff.modified.length + diff.created.length + diff.deleted.length > 0) {
      await restoreCheckpoint(cp, diff);
      this.checkpoints.pop();
      await globalSnapshotManager.undoLastTurn().catch(() => null);
      this.history.popLastTurn();
      await this.save();
      this.emit({ e: 'undone' });
      return { ok: true, text: `Put back ${diff.modified.length + diff.created.length + diff.deleted.length} file(s).` };
    }
    const { count } = await globalSnapshotManager.undoLastTurn();
    if (count === 0) return { ok: false, text: 'No changes to undo.' };
    this.history.popLastTurn();
    await this.save();
    this.emit({ e: 'undone' });
    return { ok: true, text: `Reverted changes in ${count} file(s).` };
  }

  info(): object {
    const defs = this.tools().current().map((t) => t.definition);
    const ctx = contextBreakdown(this.history.getMessages(), defs, this.config.maxContextTokens || 100000);
    const u = UsageTracker.getInstance().getSummary();
    return {
      context: ctx,
      usage: {
        requests: u.requestsCount, promptTokens: u.promptTokens, completionTokens: u.completionTokens, cacheRead: u.cacheReadTokens,
        cacheWrite: u.cacheWriteTokens, totalCost: this.cost.total, estimated: this.cost.estimated, lastPrompt: u.lastPromptTokens,
      },
      state: this.state(),
    };
  }

  tasks(): object[] {
    return getBackgroundManager().list().map((t: any) => ({ id: t.id, label: t.label, command: t.command, status: t.status, startedAt: t.startedAt }));
  }

  async stopTask(id: string): Promise<boolean> {
    try { return !!(await getBackgroundManager().stop(id)); } catch { return false; }
  }

  /** Commands the phone offers in its `/` menu: only those that make sense without a terminal. */
  /** `/mcp`, `/mcp reload`, `/mcp import`, `/mcp add <name> <command|url> [args]`: the answer is plain text for the phone. */
  async mcp(args: string): Promise<{ ok: boolean; text: string }> {
    if (this.opts.providerFactory) return { ok: true, text: 'No MCP servers.' };
    await this.mcpReady.catch(() => null);
    const manager = MCPManager.getInstance();
    const status = (): string => {
      const all = manager.getStatuses();
      if (all.length === 0) return 'No MCP servers yet.\n/mcp import takes the ones of Claude Code, /mcp add <name> <command|url> adds one.';
      return all.map((st) => {
        const mark = st.status === 'connected' ? '●' : st.status === 'connecting' ? '◌' : '○';
        const detail = st.status === 'connected' ? `${st.toolsCount} tools` : st.status === 'failed' ? `failed: ${st.error ?? 'unknown error'}` : st.status;
        return `${mark} ${st.name} · ${detail}`;
      }).join('\n');
    };
    const [sub, ...rest] = args.trim().split(/\s+/).filter(Boolean);
    try {
      switch ((sub ?? '').toLowerCase()) {
        case 'reload': case 'restart': case 'r':
          await manager.reload();
          return { ok: true, text: 'MCP servers reloaded.\n' + status() };
        case 'import': {
          const found = readClaudeServers(this.opts.cwd);
          if (Object.keys(found).length === 0) return { ok: false, text: 'No servers found in ~/.claude.json' };
          const added = saveUserServers(found);
          const skipped = Object.keys(found).filter((n) => !added.includes(n));
          await manager.reload();
          return { ok: true, text: (added.length ? `Imported: ${added.join(', ')}` : 'Nothing new to import.') + (skipped.length ? `\nAlready in devx: ${skipped.join(', ')}` : '') + '\n' + status() };
        }
        case 'add': {
          const [name, command, ...more] = rest;
          if (!name || !command) return { ok: false, text: 'Usage: /mcp add <name> <command|url> [args]\ne.g. /mcp add notion npx -y mcp-remote https://mcp.notion.com/mcp' };
          saveUserServers({ [name]: /^https?:\/\//i.test(command) ? { url: command } : { command, ...(more.length ? { args: more } : {}) } }, true);
          await manager.reload();
          const st = manager.getStatus(name);
          return st?.status === 'connected' ? { ok: true, text: `${name} connected, ${st.toolsCount} tools.` } : { ok: false, text: `${name}: ${st?.error || st?.status || 'not started'}` };
        }
        default:
          return { ok: true, text: status() };
      }
    } catch (err: any) {
      return { ok: false, text: err?.message ?? String(err) };
    }
  }

  async commandList(): Promise<Array<{ cmd: string; desc: string; custom?: boolean }>> {
    const custom = await CustomCommandManager.listCommands().catch(() => []);
    return [
      { cmd: '/compact', desc: 'Summarize the conversation to free the context' },
      { cmd: '/new', desc: 'Start a new chat in this folder' },
      { cmd: '/clear', desc: 'Clear the context and start a new chat' },
      { cmd: '/plan', desc: 'Plan mode: research and plan, no changes' },
      { cmd: '/agent', desc: 'Agent mode: edit files and run commands' },
      { cmd: '/model', desc: 'Choose the model' },
      { cmd: '/effort', desc: 'Set the reasoning effort' },
      { cmd: '/usage', desc: 'Tokens, context and cost of this chat' },
      { cmd: '/undo', desc: 'Undo what the last turn changed' },
      { cmd: '/tasks', desc: 'Background tasks' },
      { cmd: '/mcp', desc: 'MCP servers and their tools' },
      { cmd: '/mcp import', desc: 'Take the servers of Claude Code' },
      { cmd: '/mcp reload', desc: 'Start the MCP servers again' },
      { cmd: '/rename', desc: 'Rename this chat' },
      ...custom.map((c) => ({ cmd: c.cmd, desc: c.desc, custom: true })),
    ];
  }

  /** A custom command of the project (`/deploy args`) becomes a message. */
  async expandCustom(line: string): Promise<string | null> {
    const [name, ...rest] = line.trim().split(/\s+/);
    const found = await CustomCommandManager.findCommand(name);
    return found ? CustomCommandManager.expandTemplate(found.promptTemplate, rest.join(' ')) : null;
  }

  sessionId(): string {
    return this.sessions.getSession().id;
  }

  isRunning(): boolean {
    return this.running;
  }

  hasBackgroundWork(): boolean {
    try { return getBackgroundManager().list().some((t: any) => t.status === 'running'); } catch { return false; }
  }

  async close(): Promise<void> {
    this.stop();
    this.closed = true;
    delete interaction.askQuestions;
    await getBackgroundManager().stopAll().catch(() => 0);
    MCPManager.getInstance().stopAll();
  }
}

// ───────────────────────── helpers ─────────────────────────

function isTextual(mime: string, name: string): boolean {
  if (mime.startsWith('text/')) return true;
  if (/(json|xml|yaml|javascript|typescript|csv|markdown|x-sh|toml)/.test(mime)) return true;
  return /\.(txt|md|json|ya?ml|toml|csv|log|py|js|ts|tsx|jsx|java|kt|c|h|cpp|rs|go|rb|php|sh|css|html|xml|sql|gradle|ini|cfg|conf|env)$/i.test(name);
}

/** The chat as the phone draws it: user messages, thoughts, answers and tools with what they returned. Also used for a chat that runs in a terminal. */
export function snapshotItems(messages: Message[]): any[] {
  const items: any[] = [];
  const toolsById = new Map<string, any>();
  for (const m of messages) {
    if (m.role === 'system') continue;
    if (m.role === 'user') {
      const text = m.content.split('\n\n--- Attached Files ---')[0];
      if (text.startsWith(COMPACT_MARK)) { items.push({ k: 'compact', text: text.slice(COMPACT_MARK.length).trim() }); continue; }
      items.push({ k: 'user', text, files: attachedNames(m.content), images: m.images?.length ?? 0, at: m.at });
    } else if (m.role === 'assistant') {
      if (m.reasoning?.text) items.push({ k: 'thought', text: clip(m.reasoning.text, 20000), sec: m.reasoning.sec });
      // On screen the words come before the tools the model called after them.
      if (m.content?.trim()) items.push({ k: 'assistant', text: m.content });
      for (const c of m.tool_calls ?? []) {
        const tool = { k: 'tool', id: c.id, name: c.name, args: clip(JSON.stringify(c.arguments ?? {}), TOOL_ARGS_CAP), desc: describe(c.name, c.arguments), result: null as string | null };
        toolsById.set(c.id, tool);
        items.push(tool);
      }
    } else if (m.role === 'tool' && m.tool_call_id) {
      const t = toolsById.get(m.tool_call_id);
      if (t) t.result = clip(m.content, TOOL_RESULT_CAP);
    }
  }
  return items;
}

function attachedNames(content: string): string[] {
  const out: string[] = [];
  for (const m of content.matchAll(/\[Attached file(?: saved at)?: ([^\]\n]+?)(?: \(|\])/g)) out.push(path.basename(m[1]));
  return out;
}

const describe = getActionDescription;
