import { TokenUsage } from '../core/types.js';
import { PriceContext, priceUsage } from '../core/prices.js';

/**
 * Parses the JSON arguments of a tool call. Invalid JSON is reported instead of being
 * silently replaced by `{}` (a truncated write_file would otherwise look like an empty call).
 */
export function parseToolArguments(raw: string | undefined | null): { args: any; error?: string } {
  if (!raw || !raw.trim()) return { args: {} };
  try {
    const value = JSON.parse(raw);
    if (value && typeof value === 'object' && !Array.isArray(value)) return { args: value };
    return { args: {}, error: 'Tool arguments must be a JSON object.' };
  } catch {
    return {
      args: {},
      error:
        'The tool arguments were not valid JSON - the response was most likely cut off by the output token limit. ' +
        'Nothing was executed. Retry with a smaller payload (for example, split a large file into several smaller writes).'
    };
  }
}

/** What the user should see while a tool call is still being written: the path, command or query. */
export function toolTargetHint(args: string): string | undefined {
  if (!args) return undefined;
  const pathMatch = args.match(/"(?:path|filePath|targetFile)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"/);
  if (pathMatch && pathMatch[1]) return pathMatch[1];
  const cmdMatch = args.match(/"(?:command|cmd)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"/);
  if (cmdMatch && cmdMatch[1]) return cmdMatch[1].length > 30 ? cmdMatch[1].slice(0, 27) + '...' : cmdMatch[1];
  const qMatch = args.match(/"(?:query|pattern)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"/);
  if (qMatch && qMatch[1]) return `"${qMatch[1]}"`;
  return undefined;
}

/** The message inside an error body of either API ({error:{message}} or {message}). */
export function errorMessageOf(text: string): string {
  try {
    const parsed = JSON.parse(text);
    if (parsed?.error?.message) return String(parsed.error.message);
    if (typeof parsed?.error === 'string') return parsed.error;
    if (parsed?.message) return String(parsed.message);
  } catch {}
  return text;
}

/** What stands in the message when the model cannot read images. */
export function imageNotSentText(paths: string[]): string {
  return paths.map((p) => `[Attached Image: ${p} - not sent, this model cannot read images]`).join(' ');
}

export interface ProviderOptions {
  /** false: the model is known to take text only, images are replaced by a note. */
  images?: boolean;
  /** Where to look for prices and the user's own price overrides. */
  price?: Omit<PriceContext, 'model'>;
}

const count = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) && v >= 0 ? v : 0);

/** Usage of an OpenAI-format response, with the cost worked out. */
export function openAiUsage(raw: any, ctx: PriceContext, fallback?: { prompt: number; completion: number }): TokenUsage {
  const promptTokens = count(raw?.prompt_tokens) || fallback?.prompt || 0;
  const completionTokens = count(raw?.completion_tokens) || fallback?.completion || 0;
  const cacheRead =
    count(raw?.prompt_tokens_details?.cached_tokens) || count(raw?.cache_read_input_tokens) || count(raw?.prompt_cache_hit_tokens);
  const cacheWrite =
    count(raw?.prompt_tokens_details?.cache_write_tokens) || count(raw?.prompt_tokens_details?.cache_creation_tokens) || count(raw?.cache_creation_input_tokens);
  const reasoning = count(raw?.completion_tokens_details?.reasoning_tokens);

  // The endpoint sent no token counts: the numbers are our own estimate.
  const guessed = !count(raw?.prompt_tokens) && !count(raw?.completion_tokens) && !!fallback;
  const usage: TokenUsage = {
    promptTokens,
    completionTokens,
    totalTokens: count(raw?.total_tokens) || promptTokens + completionTokens,
  };
  if (cacheRead) usage.cacheReadTokens = cacheRead;
  if (cacheWrite) usage.cacheWriteTokens = cacheWrite;
  if (reasoning) usage.reasoningTokens = reasoning;

  const priced = priceUsage(ctx, usage, raw);
  if (guessed) usage.tokensEstimated = true;
  if (priced.cost !== undefined) {
    usage.cost = priced.cost;
    usage.costSource = guessed && priced.costSource !== 'free' ? 'estimated' : priced.costSource;
  }
  return usage;
}

// An error that says the request had a parameter the endpoint does not accept.
export const PARAMETER_REJECTED =
  /reasoning|thinking|effort|budget|extra_body|unknown (field|parameter|argument|variable)|unrecognized|unsupported (parameter|value)|invalid (parameter|value|request)|additional propert|extra inputs|not permitted|not allowed|unexpected/i;

/** Reads the data lines of a server-sent event stream; comments (heartbeats) and blanks are skipped. */
export async function* readSseData(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  signal: AbortSignal | undefined,
  onBytes?: (n: number) => void
): AsyncGenerator<string, void, unknown> {
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    if (signal?.aborted) return;
    const { done, value } = await reader.read();
    if (done || signal?.aborted) break;
    if (value) onBytes?.(value.byteLength);
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';
    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith(':') || !trimmed.startsWith('data:')) continue;
      yield trimmed.replace(/^data:\s*/, '');
    }
  }
  const rest = buffer.trim();
  if (rest.startsWith('data:')) yield rest.replace(/^data:\s*/, '');
}
