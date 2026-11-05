import { describe, expect, it } from 'vitest';
import { bgHex, contrastRatio, fgHex, hexToRgb, relativeLuminance, rgbTo256, supportsTruecolor } from '../../src/cli/colors.js';

describe('hex and palette conversion', () => {
  it('parses six and three digit hex colours', () => {
    expect(hexToRgb('#00f2fe')).toEqual([0, 242, 254]);
    expect(hexToRgb('ff8800')).toEqual([255, 136, 0]);
    expect(hexToRgb('#fa0')).toEqual([255, 170, 0]);
  });

  it('maps colours to the closest xterm-256 entry', () => {
    expect(rgbTo256(0, 0, 0)).toBe(16);
    expect(rgbTo256(255, 255, 255)).toBe(231);
    expect(rgbTo256(255, 0, 0)).toBe(196);
    expect(rgbTo256(0, 255, 0)).toBe(46);
    expect(rgbTo256(0, 0, 255)).toBe(21);
    expect(rgbTo256(128, 128, 128)).toBe(244); // exact entry of the grayscale ramp
    expect(rgbTo256(38, 38, 38)).toBe(235);
    // always a valid palette index
    for (const [r, g, b] of [[10, 10, 12], [200, 30, 90], [17, 200, 150], [250, 250, 249]]) {
      const index = rgbTo256(r, g, b);
      expect(index).toBeGreaterThanOrEqual(16);
      expect(index).toBeLessThanOrEqual(255);
    }
  });
});

describe('terminal capability', () => {
  it('detects truecolor from COLORTERM and honours DEVX_COLOR', () => {
    expect(supportsTruecolor({ COLORTERM: 'truecolor' })).toBe(true);
    expect(supportsTruecolor({ COLORTERM: '24bit' })).toBe(true);
    expect(supportsTruecolor({})).toBe(false);
    expect(supportsTruecolor({ COLORTERM: 'truecolor', DEVX_COLOR: '256' })).toBe(false);
    expect(supportsTruecolor({ DEVX_COLOR: 'truecolor' })).toBe(true);
  });
});

describe('fgHex / bgHex', () => {
  it('emits exact 24-bit colour on a truecolor terminal', () => {
    expect(fgHex('#00f2fe', { enabled: true, truecolor: true })('x')).toBe('\x1b[38;2;0;242;254mx\x1b[39m');
    expect(bgHex('#262626', { enabled: true, truecolor: true })('x')).toBe('\x1b[48;2;38;38;38mx\x1b[49m');
  });

  it('falls back to the closest 256-colour entry elsewhere', () => {
    expect(fgHex('#ff0000', { enabled: true, truecolor: false })('x')).toBe('\x1b[38;5;196mx\x1b[39m');
    expect(bgHex('#262626', { enabled: true, truecolor: false })('x')).toBe('\x1b[48;5;235mx\x1b[49m');
  });

  it('returns the text untouched when colours are off', () => {
    expect(fgHex('#ff0000', { enabled: false })('plain')).toBe('plain');
    expect(bgHex('#ff0000', { enabled: false })('plain')).toBe('plain');
  });
});

describe('contrast', () => {
  it('follows the WCAG definition', () => {
    expect(relativeLuminance('#000000')).toBeCloseTo(0, 5);
    expect(relativeLuminance('#ffffff')).toBeCloseTo(1, 5);
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 1);
    expect(contrastRatio('#777777', '#777777')).toBeCloseTo(1, 5);
    expect(contrastRatio('#ffffff', '#000000')).toBeCloseTo(contrastRatio('#000000', '#ffffff'), 5);
  });
});
