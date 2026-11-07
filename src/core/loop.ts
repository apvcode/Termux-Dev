import { AgentConfig, AgentEvent, LLMProvider, Message, PermissionGuard, TokenUsage, Tool } from './types.js';
import { History } from './history.js';
import { CONTEXT_USAGE_RATIO, toolSchemaTokens } from './context-stats.js';
import { t } from './i18n.js';
import { redactSecrets } from './redact.js';
import { runHooks } from './hooks.js';
import { isNetworkOrRetryableError, getHumanReadableNetworkError, waitDelay } from './network.js';

export function getActionDescription(toolName: string, args: any): string {
  const t = toolName.toLowerCase();
  if (t === 'read_file' || t === 'view_file' || t === 'read') {
    return `→ Read ${args.path || args.filePath || args.targetFile || ''}`;
  }
  if (t === 'write_file' || t === 'create_file' || t === 'write_to_file' || t === 'write') {
    return `→ Write ${args.path || args.filePath || args.targetFile || ''}`;
  }
  if (t === 'edit_file' || t === 'replace_file_content' || t === 'patch') {
    return `→ Edit ${args.path || args.filePath || args.targetFile || ''}`;
  }
  if (t === 'delete_file' || t === 'remove_file' || t === 'rm') {
    return `→ Delete ${args.path || args.filePath || ''}`;
  }
  if (t === 'make_dir' || t === 'mkdir') {
    return `→ Mkdir ${args.path || args.dirPath || ''}`;
  }
  if (t === 'list_dir' || t === 'list_files' || t === 'find_files' || t === 'ls') {
    return `→ List ${args.path || args.dirPath || '.'}`;
  }
  if (t === 'search' || t === 'grep_search' || t === 'grep') {
    return `→ Search "${args.query || args.pattern || ''}" in ${args.dir || args.path || '.'}`;
  }
  if (t === 'ask_questions' || t === 'questions') {
    return `→ Clarify: Asking questions...`;
  }
  if (t === 'bash' || t === 'execute_command' || t === 'run_command' || t === 'exec') {
    return `→ Run: ${args.command || args.cmd || ''}`;
  }
  if (t === 'background') {
    const id = args.id ? ` ${args.id}` : '';
    switch (args.action) {
      case 'start': return `→ Background: ${args.label ? `${args.label} · ` : ''}${args.command || ''}`;
      case 'list': return `→ Background: list tasks`;
      case 'output': return `→ Background: output${id}`;
      case 'wait': return `→ Background: wait${id}${args.pattern ? ` for /${args.pattern}/` : ''}`;
      case 'stop': return `→ Background: stop${id}`;
      default: return `→ Background`;
    }
  }
  if (t === 'todo_list' || t === 'update_todos') {
    return `→ Update Plan & Tasks`;
  }
  if (t.startsWith('mcp__')) {
    const [, server = 'mcp', ...rest] = toolName.split('__');
    let brief = '';
    try {
      brief = args && Object.keys(args).length ? JSON.stringify(args) : '';
    } catch {}
    return `→ MCP: ${server} · ${rest.join('__')}${brief ? ' ' + (brief.length > 120 ? brief.slice(0, 117) + '...' : brief) : ''}`;
  }
  if (t === 'plan_ready') {
    return `→ Finalize Plan`;
  }
  return `→ Executing ${toolName}...`;
}


const CONTEXT_LENGTH_ERROR =
  /context[_ ]length|maximum context|context window|too many tokens|prompt is too long|reduce the length|input is too long|exceeds? the (?:model|maximum|max)/i;

/**
 * A request that failed in a way that usually still costs money: the connection broke or timed out
 * after the request had gone out (no HTTP status), or a gateway gave up waiting for a slow model
 * (504, 524) that went on working. Rejections (400, 401, 429, ...) are not billed.
 */
function probablyBilled(err: any): boolean {
  const status = err?.status ?? err?.statusCode;
  return status === undefined || status === 0 || status === 504 || status === 524;
}

function isContextLengthError(err: any): boolean {
  const status = err?.status;
  if (status !== undefined && status !== 400 && status !== 413 && status !== 422) return false;
  return CONTEXT_LENGTH_ERROR.test(String(err?.message || ''));
}

/** Lets the caller hand the agent new user messages while it works. */
export interface RunHooks {
  /** Messages typed since the last step (oldest first); asked before every request. */
  takePending?: () => Promise<Message[]> | Message[];
  /** True when a message is waiting: the agent keeps going instead of ending the turn. */
  hasPending?: () => boolean;
  /** News from background tasks since the last step (one message for the model, or ''). */
  takeNotes?: () => string;
  /** True when such news is waiting: the agent keeps going instead of ending the turn. */
  hasNotes?: () => boolean;
}

export class Agent {
  private config: AgentConfig;
  private provider: LLMProvider;
  private tools: Map<string, Tool>;
  private history: History;
  private guard: PermissionGuard;

  constructor(
    config: AgentConfig,
    provider: LLMProvider,
    tools: Tool[],
    history: History,
    guard: PermissionGuard
  ) {
    this.config = config;
    this.provider = provider;
    this.tools = new Map(tools.map((t) => [t.name, t]));
    this.history = history;
    this.guard = guard;
  }

  async *run(signal?: AbortSignal, hooks?: RunHooks): AsyncGenerator<AgentEvent, void, unknown> {
    let iterations = 0;
    // A config without maxIterations used to make the loop exit immediately and silently.
    const maxIterations = this.config.maxIterations > 0 ? this.config.maxIterations : 100;

    while (iterations < maxIterations) {
      if (signal?.aborted) {
        return;
      }

      // Messages the user typed while the previous step ran join the conversation here, between
      // the tool results and the next request, so the model sees them before it decides what to do.
      for (const message of (await hooks?.takePending?.()) ?? []) {
        this.history.addMessage(message);
        yield { type: 'user_message', content: message.content };
      }

      // A background task ended or printed what was asked for: the model learns it before it decides.
      const news = hooks?.takeNotes?.();
      if (news) {
        this.history.addMessage({ role: 'user', content: news });
        yield { type: 'background_note', content: news };
      }

      const toolDefinitions = Array.from(this.tools.values()).map((t) => t.definition);
      // Tool schemas are sent with every request and count against the context window.
      const schemaTokens = toolSchemaTokens(toolDefinitions);
      const promptBudget = Math.max(
        1000,
        Math.floor(this.config.maxContextTokens * CONTEXT_USAGE_RATIO) - schemaTokens
      );

      const wasCompacted = this.history.pruneToLimit(promptBudget);
      if (wasCompacted) {
        yield { type: 'system', message: 'Context compacted to fit model context window.' };
      }

      const request: any = {
        messages: this.history.getMessages(),
        tools: toolDefinitions,
        signal,
      };

      if (request.tools.length === 0) {
        delete request.tools;
      }

      let response: any;
      let attempt = 0;
      let contextRetries = 0;
      const maxRetries = 4;
      // A request that goes out and gets no answer (cancelled, connection lost, gateway timeout) is
      // usually billed all the same, and a retry sends the whole conversation again. Such requests
      // are counted as an estimate instead of being left out of the cost.
      let receivedChars = 0;
      const lostUsage = (): TokenUsage | undefined =>
        this.provider.lostRequestUsage?.(this.history.getTotalTokens() + schemaTokens, Math.ceil(receivedChars * 0.33));

      while (true) {
        if (signal?.aborted) return;
        receivedChars = 0;
        try {
          if (this.provider.chatStream) {
            let receivedAnyChunk = false;
            for await (const chunk of this.provider.chatStream(request)) {
              if (signal?.aborted) {
                const lost = lostUsage();
                if (lost) yield { type: 'usage', usage: lost };
                return;
              }
              receivedAnyChunk = true;
              if (chunk.type === 'reasoning_delta') {
                receivedChars += chunk.delta.length;
                yield { type: 'reasoning_delta', delta: chunk.delta };
              } else if (chunk.type === 'content_delta') {
                receivedChars += chunk.delta.length;
                yield { type: 'text_delta', delta: chunk.delta };
              } else if (chunk.type === 'tool_generating') {
                yield { type: 'tool_generating', name: chunk.name, bytes: chunk.bytes, targetHint: chunk.targetHint };
              } else if (chunk.type === 'done') {
                response = chunk.response;
              }
            }
            if (response) {
              break;
            }
            if (!receivedAnyChunk) {
              throw new Error('Provider stream ended unexpectedly without receiving any data.');
            }
            break;
          } else {
            response = await this.provider.chat(request);
            break;
          }
        } catch (err: any) {
          if (signal?.aborted || err.name === 'AbortError' || err.message?.includes('aborted')) {
            const lost = lostUsage();
            if (lost) yield { type: 'usage', usage: lost };
            return;
          }

          // The provider says the prompt is too long (our estimate was too optimistic):
          // shrink the history harder and retry instead of failing the whole session.
          if (contextRetries < 2 && isContextLengthError(err)) {
            contextRetries++;
            const target = Math.floor(this.history.getTotalTokens() * (contextRetries === 1 ? 0.6 : 0.35));
            this.history.pruneToLimit(Math.max(500, target));
            request.messages = this.history.getMessages();
            yield { type: 'system', message: 'Provider reported the context is too long. Compacted history, retrying.' };
            continue;
          }

          const isNetwork = isNetworkOrRetryableError(err);
          if (isNetwork && probablyBilled(err)) {
            const lost = lostUsage();
            if (lost) yield { type: 'usage', usage: lost };
          }
          if (isNetwork && attempt < maxRetries) {
            attempt++;
            const delayMs = Math.min(1500 * Math.pow(1.5, attempt - 1), 8000);
            yield {
              type: 'reconnecting',
              attempt,
              maxAttempts: maxRetries,
              delayMs,
              reason: getHumanReadableNetworkError(err)
            };

            await waitDelay(delayMs, signal);
            if (signal?.aborted) return;

            yield { type: 'reconnected', attempt };
            continue;
          }

          const errMsg = isNetwork
            ? t('net.failedAfter', { n: attempt, reason: err.message })
            : `API Error: ${err.message}`;
          yield { type: 'error', message: errMsg, isFatal: true };
          return;
        }
      }

      if (signal?.aborted) {
        return;
      }

      for (const notice of this.provider.drainNotices?.() ?? []) {
        yield { type: 'system', message: notice };
      }

      if (!response) {
        response = { content: '' };
      }

      const assistantMsg: Message = {
        role: 'assistant',
        content: response.content || '',
      };

      if (response.toolCalls && response.toolCalls.length > 0) {
        assistantMsg.tool_calls = response.toolCalls;
      }
      if (response.thinkingBlocks && response.thinkingBlocks.length > 0) {
        assistantMsg.thinking_blocks = response.thinkingBlocks;
      }

      this.history.addMessage(assistantMsg);

      if (response.usage) {
        yield { type: 'usage', usage: response.usage };
      }

      // If streaming wasn't used or to notify completed text
      if (assistantMsg.content && !this.provider.chatStream) {
        yield { type: 'text', content: assistantMsg.content };
      }

      if (!response.toolCalls || response.toolCalls.length === 0) {
        // The user wrote something while the model was finishing: answer it instead of stopping.
        if (hooks?.hasPending?.() || hooks?.hasNotes?.()) {
          iterations++;
          continue;
        }
        break;
      }

      const executedToolCallIds = new Set<string>();
      try {
        for (const call of response.toolCalls) {
          if (signal?.aborted) return;

          const actionDesc = getActionDescription(call.name, call.arguments);
          yield { type: 'tool_start', id: call.id, name: call.name, argsRaw: JSON.stringify(call.arguments), actionDesc };
          if (signal?.aborted) return;

          let result = '';
          // Pictures a tool wants the model to see (read_file on an image) travel with its result.
          const pictures: NonNullable<Message['images']> = [];
          const tool = this.tools.get(call.name);

          if (!tool) {
            result = `Error: Tool '${call.name}' not found.`;
          } else if (call.argsError) {
            // Never run a tool with arguments we could not parse ({} could mean something else).
            result = `Error: ${call.argsError}`;
          } else {
            try {
              tool.validateArgs(call.arguments);
              // A hook of the user may say no before the user is even asked.
              const pre = await runHooks(this.config.hooks, 'PreToolUse', tool.name, call.arguments);
              if (pre.blocked) {
                result = `Blocked by a hook: ${pre.blocked}`;
              } else {
                const allowed = !this.guard.check(tool.name, call.arguments)
                  || await this.guard.askUser(tool.name, call.arguments);

                // Cancellation may arrive while waiting for confirmation.
                if (signal?.aborted) return;
                result = allowed
                  ? await tool.execute(call.arguments, this.config, { signal, attachImage: (image) => pictures.push(image) })
                  : 'Action denied by user.';
                if (allowed) {
                  const post = await runHooks(this.config.hooks, 'PostToolUse', tool.name, call.arguments, result);
                  if (post.notes.length > 0) result += `\n[hook] ${post.notes.join('\n[hook] ')}`;
                }
              }
            } catch (err: any) {
              result = signal?.aborted
                ? '[Operation cancelled by user]'
                : `Error executing tool: ${err.message}`;
            }
          }

          // Keys and tokens in the output must not reach the model, the chat or the saved session.
          result = redactSecrets(result);

          // Save the result before yielding: the UI can stop consuming at any event.
          this.history.addMessage({
            role: 'tool',
            content: result,
            tool_call_id: call.id,
            name: call.name,
            ...(pictures.length > 0 ? { images: pictures } : {}),
          });
          executedToolCallIds.add(call.id);
          yield { type: 'tool_end', id: call.id, name: call.name, result };
        }
      } finally {
        // Keep every tool call paired with a response, even if the consumer exits early.
        for (const call of response.toolCalls) {
          if (!executedToolCallIds.has(call.id)) {
            this.history.addMessage({
              role: 'tool',
              content: '[Operation cancelled by user]',
              tool_call_id: call.id,
              name: call.name,
            });
          }
        }
      }

      iterations++;
    }

    if (iterations >= maxIterations) {
      yield { type: 'system', message: `Reached max iterations (${maxIterations}).` };
    }
  }
}
