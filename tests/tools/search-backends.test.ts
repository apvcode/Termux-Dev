import { describe, expect, it } from 'vitest';
import { parseBing, parseDuckDuckGo } from '../../src/tools/search-backends.js';

describe('web search pages', () => {
  it('reads DuckDuckGo results whatever order the attributes come in, and drops advertisements', () => {
    const html = `
      <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fnodejs.org%2F&amp;rut=abc">Node.js &#8212; Run it</a>
      <a class="result__snippet" href="x">A free &amp; open <b>runtime</b> for caf&#233;s</a>
      <a class="result__a" href="https://duckduckgo.com/y.js?ad_domain=shop.example">An ad</a>
      <a class="result__snippet" href="x">Buy now</a>
      <a class="result__a" href="https://example.org/doc">Docs</a>
      <a class="result__snippet" href="x">Second</a>`;
    const hits = parseDuckDuckGo(html);
    expect(hits.map((h) => h.link)).toEqual(['https://nodejs.org/', 'https://example.org/doc']);
    expect(hits[0].title).toBe('Node.js — Run it');
    expect(hits[0].snippet).toBe('A free & open runtime for cafés');
  });

  it('reads Bing results and finds the address in its redirect', () => {
    const target = 'https://developer.mozilla.org/en-US/docs/Web/API/fetch';
    const u = 'a1' + Buffer.from(target).toString('base64url');
    const html = `<ol><li class="b_algo" data-id><h2><a href="https://www.bing.com/ck/a?!&amp;&amp;p=1&amp;u=${u}&amp;ntb=1">Fetch API &#8211; MDN</a></h2><div class="b_caption"><p class="b_lineclamp2">Fetch is <strong>great</strong>.</p></div></li>
      <li class="b_algo"><h2><a href="https://example.com/">Plain</a></h2></li></ol>`;
    const hits = parseBing(html);
    expect(hits[0]).toEqual({ title: 'Fetch API – MDN', link: target, snippet: 'Fetch is great.' });
    expect(hits[1].link).toBe('https://example.com/');
  });

  it('gives nothing for a page without results', () => {
    expect(parseDuckDuckGo('<html>anomaly-modal</html>')).toEqual([]);
    expect(parseBing('<html></html>')).toEqual([]);
  });
});
