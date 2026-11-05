import dns from 'dns/promises';
import net from 'net';
import { Tool } from '../core/types.js';

function stripHtml(html: string): string {
  return html
    .replace(/<script\b[\s\S]*?<\/script>/gi, '')
    .replace(/<style\b[\s\S]*?<\/style>/gi, '')
    .replace(/<svg\b[\s\S]*?<\/svg>/gi, '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&quot;/g, '"')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&#39;/g, "'")
    .replace(/&nbsp;/g, ' ')
    // &amp; last, otherwise "&amp;lt;" would be decoded twice into "<"
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

// ───────────── SSRF protection for fetch_url ─────────────
// A prompt-injected page must not be able to make the agent talk to localhost,
// the LAN or cloud metadata endpoints (169.254.169.254). Set
// DEVX_ALLOW_PRIVATE_NETWORK=1 to fetch from local servers on purpose.

function isPrivateIPv4(ip: string): boolean {
  const [a, b, c] = ip.split('.').map(Number);
  return (
    a === 0 || a === 10 || a === 127 ||
    (a === 100 && b >= 64 && b <= 127) || // carrier-grade NAT
    (a === 169 && b === 254) ||           // link-local + cloud metadata
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 0 && c === 0) ||
    (a === 192 && b === 168) ||
    (a === 198 && (b === 18 || b === 19)) ||
    a >= 224                              // multicast / reserved / broadcast
  );
}

function parseIPv6(ip: string): number[] | null {
  let s = ip.split('%')[0].toLowerCase();
  const embedded = s.match(/(\d+\.\d+\.\d+\.\d+)$/);
  if (embedded) {
    const o = embedded[1].split('.').map(Number);
    s = s.slice(0, -embedded[1].length) + ((o[0] << 8) | o[1]).toString(16) + ':' + ((o[2] << 8) | o[3]).toString(16);
  }
  const parts = s.split('::');
  if (parts.length > 2) return null;
  const head = parts[0] ? parts[0].split(':') : [];
  const tail = parts.length === 2 && parts[1] ? parts[1].split(':') : [];
  let groups: string[];
  if (parts.length === 1) {
    groups = head;
  } else {
    const missing = 8 - head.length - tail.length;
    if (missing < 0) return null;
    groups = [...head, ...Array(missing).fill('0'), ...tail];
  }
  if (groups.length !== 8) return null;
  const nums = groups.map(g => parseInt(g, 16));
  return nums.some(n => Number.isNaN(n)) ? null : nums;
}

function isPrivateIPv6(ip: string): boolean {
  const g = parseIPv6(ip);
  if (!g) return true;
  const v4 = (hi: number, lo: number) => isPrivateIPv4(`${hi >> 8}.${hi & 255}.${lo >> 8}.${lo & 255}`);
  if (g.slice(0, 7).every(n => n === 0) && g[7] <= 1) return true;        // :: and ::1
  if ((g[0] & 0xffc0) === 0xfe80) return true;                            // fe80::/10 link-local
  if ((g[0] & 0xfe00) === 0xfc00) return true;                            // fc00::/7 unique local
  if ((g[0] & 0xff00) === 0xff00) return true;                            // multicast
  if (g.slice(0, 5).every(n => n === 0) && g[5] === 0xffff) return v4(g[6], g[7]); // ::ffff:a.b.c.d
  if (g[0] === 0x64 && g[1] === 0xff9b) return v4(g[6], g[7]);            // NAT64
  if (g[0] === 0x2002) return v4(g[1], g[2]);                             // 6to4
  return false;
}

export function isPrivateAddress(ip: string): boolean {
  if (net.isIPv4(ip)) return isPrivateIPv4(ip);
  if (net.isIPv6(ip)) return isPrivateIPv6(ip);
  return true; // not an address we understand: refuse
}

async function assertPublicHost(rawHostname: string): Promise<void> {
  if (process.env.DEVX_ALLOW_PRIVATE_NETWORK === '1') return;
  const host = rawHostname.replace(/^\[|\]$/g, '').replace(/\.$/, '').toLowerCase();
  const blocked = () =>
    new Error(`Blocked: ${rawHostname} points to a local or private network address (set DEVX_ALLOW_PRIVATE_NETWORK=1 to allow).`);

  if (net.isIP(host)) {
    if (isPrivateAddress(host)) throw blocked();
    return;
  }
  if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || host.endsWith('.internal')) {
    throw blocked();
  }
  const addresses = await dns.lookup(host, { all: true });
  if (addresses.length === 0 || addresses.some(a => isPrivateAddress(a.address))) throw blocked();
}

const MAX_REDIRECTS = 5;
const MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

/** fetch() that validates the target and every redirect hop against private networks. */
export async function fetchPublic(startUrl: string, headers: Record<string, string>, timeoutMs: number): Promise<Response> {
  const signal = AbortSignal.timeout(timeoutMs);
  let current = new URL(startUrl);
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    if (current.protocol !== 'http:' && current.protocol !== 'https:') {
      throw new Error(`Unsupported protocol: ${current.protocol}`);
    }
    await assertPublicHost(current.hostname);
    const res = await fetch(current, { headers, signal, redirect: 'manual' });
    const location = res.headers.get('location');
    if (res.status >= 300 && res.status < 400 && location) {
      try { await res.body?.cancel(); } catch {}
      current = new URL(location, current);
      continue;
    }
    return res;
  }
  throw new Error('Too many redirects');
}

async function readTextLimited(res: Response, maxBytes: number): Promise<string> {
  const reader = res.body?.getReader();
  if (!reader) return (await res.text()).slice(0, maxBytes);
  const chunks: Buffer[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(Buffer.from(value));
    total += value.byteLength;
    if (total >= maxBytes) {
      try { await reader.cancel(); } catch {}
      break;
    }
  }
  return Buffer.concat(chunks).toString('utf8');
}

export const webSearchTool: Tool = {
  name: 'web_search',
  definition: {
    name: 'web_search',
    description: 'Search the web using DuckDuckGo to get fresh documentation, API references, library examples, or solutions for code bugs.',
    parameters: {
      type: 'object',
      properties: {
        query: { type: 'string', description: 'Search query' }
      },
      required: ['query']
    }
  },
  validateArgs(args: any) {
    if (!args.query || typeof args.query !== 'string') throw new Error('query is required');
  },
  async execute(args: { query: string }) {
    try {
      const encoded = encodeURIComponent(args.query);
      const url = `https://html.duckduckgo.com/html/?q=${encoded}`;
      
      const res = await fetch(url, {
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
        },
        signal: AbortSignal.timeout(10000)
      });

      if (!res.ok) {
        throw new Error(`DuckDuckGo returned status ${res.status}`);
      }

      const html = await res.text();
      const results: Array<{ title: string; snippet: string; link: string }> = [];

      // Extract results from DDG HTML
      const resultRegex = /<a class="result__url" href="([^"]+)".*?<a class="result__snippet[^>]*>(.*?)<\/a>/gs;
      const titleRegex = /<a class="result__a" href="([^"]+)">(.*?)<\/a>/g;

      const titles: Array<{ link: string; title: string }> = [];
      let match;
      while ((match = titleRegex.exec(html)) !== null && titles.length < 6) {
        let rawLink = match[1];
        if (rawLink.includes('uddg=')) {
          const urlParam = rawLink.split('uddg=')[1]?.split('&')[0];
          if (urlParam) rawLink = decodeURIComponent(urlParam);
        }
        titles.push({
          link: rawLink,
          title: stripHtml(match[2])
        });
      }

      const snippetRegex = /<a class="result__snippet[^>]*>(.*?)<\/a>/g;
      const snippets: string[] = [];
      while ((match = snippetRegex.exec(html)) !== null && snippets.length < 6) {
        snippets.push(stripHtml(match[1]));
      }

      for (let i = 0; i < titles.length; i++) {
        results.push({
          title: titles[i].title,
          link: titles[i].link,
          snippet: snippets[i] || ''
        });
      }

      if (results.length === 0) {
        // Fallback: try Instant Answer API
        try {
          const instantRes = await fetch(`https://api.duckduckgo.com/?q=${encoded}&format=json`, {
            signal: AbortSignal.timeout(5000)
          });
          if (instantRes.ok) {
            const data: any = await instantRes.json();
            if (data.AbstractText) {
              return `Summary: ${data.AbstractText}\nSource: ${data.AbstractURL || ''}`;
            }
            if (data.RelatedTopics && data.RelatedTopics.length > 0) {
              const items = data.RelatedTopics.slice(0, 5)
                .filter((t: any) => t.Text && t.FirstURL)
                .map((t: any) => `• ${t.Text}\n  URL: ${t.FirstURL}`);
              if (items.length > 0) return items.join('\n\n');
            }
          }
        } catch {}

        return `No web results found for "${args.query}".`;
      }

      const formatted = results.map((r, i) => 
        `[${i + 1}] ${r.title}\nURL: ${r.link}\nSnippet: ${r.snippet}`
      ).join('\n\n');

      return formatted;
    } catch (err: any) {
      return `Web search failed: ${err.message}`;
    }
  }
};

export const fetchUrlTool: Tool = {
  name: 'fetch_url',
  definition: {
    name: 'fetch_url',
    description: 'Fetch and read the text content of a public URL or documentation web page.',
    parameters: {
      type: 'object',
      properties: {
        url: { type: 'string', description: 'Web URL (http/https) to fetch' }
      },
      required: ['url']
    }
  },
  validateArgs(args: any) {
    if (!args.url || typeof args.url !== 'string') throw new Error('url is required');
  },
  async execute(args: { url: string }) {
    try {
      let targetUrl = args.url.trim();
      if (!targetUrl.startsWith('http://') && !targetUrl.startsWith('https://')) {
        targetUrl = `https://${targetUrl}`;
      }

      const res = await fetchPublic(
        targetUrl,
        {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
        },
        12000
      );

      if (!res.ok) {
        throw new Error(`HTTP Error ${res.status}: ${res.statusText}`);
      }

      const html = await readTextLimited(res, MAX_RESPONSE_BYTES);
      const cleanText = stripHtml(html);

      if (!cleanText) {
        return 'Webpage returned empty text content.';
      }

      const maxLength = 10000;
      if (cleanText.length > maxLength) {
        return cleanText.substring(0, maxLength) + `\n\n[... truncated ${cleanText.length - maxLength} characters]`;
      }

      return cleanText;
    } catch (err: any) {
      return `Failed to fetch URL: ${err.message}`;
    }
  }
};
