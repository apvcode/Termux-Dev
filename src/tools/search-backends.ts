/**
 * Web search without a key: DuckDuckGo's HTML page, and Bing's as the second try. Both answer a bot-check page ("anomaly")
 * to some requests some of the time, so a search is tried a few ways and the first one that gives results wins.
 */
export interface SearchHit { title: string; link: string; snippet: string }

const FIREFOX = 'Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0';
const CHROME = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

const entities: Record<string, string> = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&#39;': "'", '&#x27;': "'", '&nbsp;': ' ', '&#x2F;': '/' };
function clean(html: string): string {
  return html
    .replace(/<[^>]*>/g, '')
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => { try { return String.fromCodePoint(parseInt(h, 16)); } catch { return ''; } })
    .replace(/&#(\d+);/g, (_, d) => { try { return String.fromCodePoint(Number(d)); } catch { return ''; } })
    .replace(/&(?:amp|lt|gt|quot|nbsp);/g, (m) => entities[m] ?? m)
    .replace(/\s+/g, ' ')
    .trim();
}

const hasCyrillic = (s: string) => /[Ѐ-ӿ]/.test(s);

async function get(url: string, init: { method?: string; body?: string; ua: string; lang: string }): Promise<string> {
  const res = await fetch(url, {
    method: init.method ?? 'GET',
    headers: {
      'User-Agent': init.ua,
      'Accept-Language': init.lang,
      Accept: 'text/html,application/xhtml+xml',
      ...(init.body ? { 'Content-Type': 'application/x-www-form-urlencoded' } : {}),
    },
    body: init.body,
    signal: AbortSignal.timeout(12000),
  });
  const text = await res.text();
  if (!res.ok && res.status !== 202) throw new Error(`status ${res.status}`);
  return text;
}

const attr = (tag: string, name: string): string | undefined => new RegExp(`${name}="([^"]*)"`).exec(tag)?.[1];

export function parseDuckDuckGo(html: string): SearchHit[] {
  const hits: SearchHit[] = [];
  const snippets = [...html.matchAll(/<a\b[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)<\/a>/gs)].map((m) => clean(m[1]));
  for (const m of html.matchAll(/<a\b([^>]*class="[^"]*result__a[^"]*"[^>]*)>(.*?)<\/a>/gs)) {
    let link = (attr(m[1], 'href') ?? '').replace(/&amp;/g, '&');
    const uddg = /[?&]uddg=([^&]+)/.exec(link)?.[1];
    if (uddg) link = decodeURIComponent(uddg);
    if (link.startsWith('//')) link = 'https:' + link;
    // Advertisements go through a click tracker.
    if (!/^https?:\/\//.test(link) || /duckduckgo\.com\/y\.js/.test(link)) continue;
    hits.push({ title: clean(m[2]), link, snippet: snippets[hits.length] ?? '' });
    if (hits.length >= 8) break;
  }
  return hits;
}

/** Bing hides the address in a redirect: u=a1 followed by the address in base64. */
function bingLink(href: string): string {
  const h = href.replace(/&amp;/g, '&');
  const u = /[?&]u=a1([^&]+)/.exec(h)?.[1];
  if (!u) return h;
  try { return Buffer.from(u.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8'); } catch { return h; }
}

export function parseBing(html: string): SearchHit[] {
  const hits: SearchHit[] = [];
  for (const block of html.split(/<li class="b_algo"/).slice(1)) {
    const a = /<h2[^>]*>\s*<a\b([^>]*)>(.*?)<\/a>/s.exec(block);
    if (!a) continue;
    const link = bingLink(attr(a[1], 'href') ?? '');
    if (!/^https?:\/\//.test(link)) continue;
    const snippet = /<p[^>]*class="[^"]*b_lineclamp[^"]*"[^>]*>(.*?)<\/p>/s.exec(block)?.[1] ?? /<div class="b_caption[^"]*"[^>]*>.*?<p[^>]*>(.*?)<\/p>/s.exec(block)?.[1] ?? '';
    hits.push({ title: clean(a[2]), link, snippet: clean(snippet) });
    if (hits.length >= 8) break;
  }
  return hits;
}

const isBlocked = (html: string) => /anomaly(?:-modal|\.js)|id="challenge-form"|g-recaptcha|unusual traffic/i.test(html);

/** Tries the ways in turn; returns the first that has results, with the name of the service. */
export async function searchWeb(query: string): Promise<{ hits: SearchHit[]; via: string } | null> {
  const lang = hasCyrillic(query) ? 'ru-RU,ru;q=0.9,en;q=0.5' : 'en-US,en;q=0.9';
  const q = encodeURIComponent(query);
  const attempts: Array<[string, () => Promise<SearchHit[]>]> = [
    ['DuckDuckGo', async () => { const h = await get('https://html.duckduckgo.com/html/', { method: 'POST', body: `q=${q}`, ua: FIREFOX, lang }); return isBlocked(h) ? [] : parseDuckDuckGo(h); }],
    ['Bing', async () => parseBing(await get(`https://www.bing.com/search?q=${q}&setlang=${hasCyrillic(query) ? 'ru' : 'en'}`, { ua: FIREFOX, lang }))],
    ['DuckDuckGo', async () => { const h = await get(`https://html.duckduckgo.com/html/?q=${q}`, { ua: CHROME, lang }); return isBlocked(h) ? [] : parseDuckDuckGo(h); }],
    ['Bing', async () => parseBing(await get(`https://www.bing.com/search?q=${q}`, { ua: CHROME, lang }))],
  ];
  for (const [via, run] of attempts) {
    try {
      const hits = await run();
      if (hits.length > 0) return { hits, via };
    } catch { /* the next way */ }
  }
  return null;
}
