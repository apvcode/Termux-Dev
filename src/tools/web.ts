import { Tool } from '../core/types.js';

function stripHtml(html: string): string {
  return html
    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
    .replace(/<style\b[^<]*(?:(?!<\/style>)<[^<]*)*<\/style>/gi, '')
    .replace(/<svg\b[^<]*(?:(?!<\/svg>)<[^<]*)*<\/svg>/gi, '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&#39;/g, "'")
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
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

      const res = await fetch(targetUrl, {
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
        },
        signal: AbortSignal.timeout(12000)
      });

      if (!res.ok) {
        throw new Error(`HTTP Error ${res.status}: ${res.statusText}`);
      }

      const html = await res.text();
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
