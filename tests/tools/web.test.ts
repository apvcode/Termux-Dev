import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchUrlTool, isPrivateAddress } from '../../src/tools/web.js';
import { parseToolArguments } from '../../src/providers/openai.js';

// A public IP literal: no DNS lookup, and the network is never touched (fetch is mocked).
const PUBLIC_URL = 'http://93.184.216.34/page';

afterEach(() => {
  vi.unstubAllGlobals();
  delete process.env.DEVX_ALLOW_PRIVATE_NETWORK;
});

describe('isPrivateAddress', () => {
  it.each([
    '127.0.0.1', '127.1.2.3', '10.0.0.5', '172.16.0.1', '172.31.255.255', '192.168.1.1',
    '169.254.169.254', '100.64.0.1', '0.0.0.0', '224.0.0.1', '255.255.255.255',
    '::1', '::', 'fe80::1', 'fc00::1', 'fd12:3456::1', 'ff02::1',
    '::ffff:127.0.0.1', '::ffff:7f00:1', '::ffff:169.254.169.254', '64:ff9b::7f00:1',
  ])('treats %s as private', (ip) => {
    expect(isPrivateAddress(ip)).toBe(true);
  });

  it.each([
    '93.184.216.34', '8.8.8.8', '1.1.1.1', '172.15.0.1', '172.32.0.1', '192.169.0.1',
    '2606:4700:4700::1111', '2001:4860:4860::8888', '::ffff:8.8.8.8',
  ])('treats %s as public', (ip) => {
    expect(isPrivateAddress(ip)).toBe(false);
  });

  it('refuses anything that is not an IP address', () => {
    expect(isPrivateAddress('not-an-ip')).toBe(true);
  });
});

describe('fetch_url SSRF protection', () => {
  it.each([
    'http://127.0.0.1:8080/',
    'http://localhost:3000/admin',
    'http://foo.localhost/',
    'http://[::1]/',
    'http://169.254.169.254/latest/meta-data/',
    'http://10.0.0.1/',
    'http://192.168.0.1/',
    'http://2130706433/', // 127.0.0.1 as a single integer
    'http://0x7f.0.0.1/',
    'http://printer.local/',
  ])('blocks %s without sending a request', async (url) => {
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    const result = await fetchUrlTool.execute({ url });
    expect(result).toMatch(/Failed to fetch URL: Blocked/);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('blocks a public URL that redirects to an internal address', async () => {
    const fetchSpy = vi.fn(async () =>
      new Response(null, { status: 302, headers: { location: 'http://169.254.169.254/latest/meta-data/' } })
    );
    vi.stubGlobal('fetch', fetchSpy);
    const result = await fetchUrlTool.execute({ url: PUBLIC_URL });
    expect(result).toMatch(/Blocked/);
    expect(fetchSpy).toHaveBeenCalledTimes(1); // the internal hop is never requested
  });

  it('follows redirects between public addresses', async () => {
    let n = 0;
    vi.stubGlobal('fetch', vi.fn(async () =>
      ++n === 1
        ? new Response(null, { status: 301, headers: { location: 'http://8.8.8.8/final' } })
        : new Response('<html><body><p>final page</p></body></html>', { status: 200 })
    ));
    expect(await fetchUrlTool.execute({ url: PUBLIC_URL })).toContain('final page');
  });

  it('gives up after too many redirects', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(null, { status: 302, headers: { location: 'http://8.8.8.8/loop' } })
    ));
    expect(await fetchUrlTool.execute({ url: PUBLIC_URL })).toMatch(/Too many redirects/);
  });

  it('can be allowed explicitly for local documentation servers', async () => {
    process.env.DEVX_ALLOW_PRIVATE_NETWORK = '1';
    vi.stubGlobal('fetch', vi.fn(async () => new Response('local docs', { status: 200 })));
    expect(await fetchUrlTool.execute({ url: 'http://127.0.0.1:4000/' })).toContain('local docs');
  });

  it('does not read more than the size limit from a huge response', async () => {
    const big = 'a'.repeat(5 * 1024 * 1024);
    vi.stubGlobal('fetch', vi.fn(async () => new Response(big, { status: 200 })));
    const result = await fetchUrlTool.execute({ url: PUBLIC_URL });
    expect(result.length).toBeLessThan(10200);
    expect(result).toContain('truncated');
  });
});

describe('parseToolArguments', () => {
  it('parses objects and treats empty argument strings as "no arguments"', () => {
    expect(parseToolArguments('{"path":"a.txt"}')).toEqual({ args: { path: 'a.txt' } });
    expect(parseToolArguments('')).toEqual({ args: {} });
    expect(parseToolArguments('   ')).toEqual({ args: {} });
    expect(parseToolArguments(undefined)).toEqual({ args: {} });
  });

  it('reports cut-off JSON instead of silently using {}', () => {
    const result = parseToolArguments('{"path":"a.txt","content":"const x = 1;\\nconst y');
    expect(result.args).toEqual({});
    expect(result.error).toMatch(/not valid JSON/);
    expect(result.error).toMatch(/Nothing was executed/);
  });

  it('rejects arguments that are not an object', () => {
    expect(parseToolArguments('[1,2]').error).toMatch(/JSON object/);
    expect(parseToolArguments('"text"').error).toMatch(/JSON object/);
  });
});
