import pc from 'picocolors';
import { getCurrentTheme } from './theme.js';
import { DEVX_LOGO } from './logo.js';
import { DEVX_VERSION } from '../core/types.js';

export type BannerMode = 'clean' | 'full' | 'minimal' | 'off';

export function renderBannerLines(
  mode: BannerMode = 'full',
  version: string = DEVX_VERSION,
  cols: number = 80
): string[] {
  if (mode === 'off') {
    return [];
  }

  const theme = getCurrentTheme();

  if (mode === 'clean') {
    return [pc.bold('devx') + pc.dim(`  v${version}`)];
  }

  if (mode === 'minimal') {
    return [
      theme.boldFn('TERMUX·DEV') + ' ' + pc.dim(`v${version}`) + pc.dim(' · Type /help for commands')
    ];
  }

  // Full ASCII Banner
  if (cols < 68) {
    // Compact ASCII for narrow mobile screens (~26 chars)
    return [
      ...DEVX_LOGO.map((l) => '  ' + theme.colorFn(l)),
      '  ' + theme.boldFn(`v${version}`)
    ];
  }

  // Full wide ASCII banner (53 chars)
  const indent = '   ';
  return [
    indent + theme.colorFn('▀▀▀█▀▀▀ █▀▀▀ █▀▀█ █▄ ▄█ █  █ ▀▄ ▄▀    █▀▀▄ █▀▀▀ █   █'),
    indent + theme.colorFn('   █    █▀▀▀ █▄▄▀ █ █ █ █  █   █   ▀▀ █  █ █▀▀▀ █   █'),
    indent + theme.colorFn('   █    █▄▄▄ █ ▀▄ █   █ ▀▄▄▀ ▄▀ ▀▄    █▄▄▀ █▄▄▄  ▀▄▀ '),
    indent + theme.boldFn(`v${version}`)
  ];
}

export function drawBanner(
  mode: BannerMode = 'full',
  version: string = DEVX_VERSION,
  cols: number = 80
): void {
  const lines = renderBannerLines(mode, version, cols);
  if (lines.length > 0) {
    console.log();
    for (const l of lines) {
      console.log(l);
    }
    console.log();
  }
}
