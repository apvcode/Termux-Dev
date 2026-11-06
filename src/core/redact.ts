/**
 * Hides secrets in the output of a tool before it reaches the model, the chat and the saved
 * session: API keys, tokens and private keys that show up in a file, in `env` or in a log.
 * It works on well-known shapes; a secret of an unknown shape can still get through, which is why
 * reading credential files is also asked about (see the permission guard).
 */

const HIDDEN = '[hidden secret]';

/** Keeps the first characters of a token (enough to recognise which key it is), hides the rest. */
const keepHead = (value: string, keep: number) => `${value.slice(0, keep)}…${HIDDEN}`;

const PATTERNS: Array<{ re: RegExp; replace: (match: string, ...groups: string[]) => string }> = [
  // Private key blocks
  { re: /-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|$)/g, replace: () => `[hidden private key]` },
  // sk-..., sk-or-v1-..., sk-ant-... (OpenAI, OpenRouter, Anthropic and compatible gateways)
  { re: /\bsk-[A-Za-z0-9][A-Za-z0-9_\-]{15,}/g, replace: (m) => keepHead(m, 5) },
  // GitHub tokens
  { re: /\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})/g, replace: (m) => keepHead(m, 4) },
  // Slack tokens
  { re: /\bxox[abprs]-[A-Za-z0-9-]{10,}/g, replace: (m) => keepHead(m, 5) },
  // AWS access key ids, Google API keys
  { re: /\b(?:AKIA|ASIA)[0-9A-Z]{16}\b/g, replace: (m) => keepHead(m, 4) },
  { re: /\bAIza[0-9A-Za-z_\-]{35}\b/g, replace: (m) => keepHead(m, 4) },
  // Authorization headers
  { re: /\b(Bearer|Basic)\s+[A-Za-z0-9._~+\/=\-]{16,}/g, replace: (_m, scheme) => `${scheme} ${HIDDEN}` },
  // "apiKey": "value" and api_key=value style assignments (the name is kept, the value is hidden)
  {
    re: /((?:["']?)(?:api[_-]?key|apikey|secret|client[_-]?secret|access[_-]?token|auth[_-]?token|token|password|passwd)(?:["']?)\s*[:=]\s*["']?)([^\s"',}\]]{8,})(["']?)/gi,
    replace: (_m, head, _value, tail) => `${head}${HIDDEN}${tail}`,
  },
];

export function redactSecrets(text: string): string {
  if (!text || typeof text !== 'string') return text;
  let out = text;
  for (const { re, replace } of PATTERNS) out = out.replace(re, replace as any);
  return out;
}

/** Whether a text holds something that looks like a secret (for tests and notices). */
export function containsSecret(text: string): boolean {
  return redactSecrets(text) !== text;
}
