import { describe, it, expect } from 'vitest';
import { installPackageTool } from '../../src/tools/packages.js';

describe('installPackageTool', () => {
  it('validates safe package names', () => {
    expect(() => installPackageTool.validateArgs({ package: 'express' })).not.toThrow();
    expect(() => installPackageTool.validateArgs({ package: '@types/node@^20.0.0' })).not.toThrow();
    expect(() => installPackageTool.validateArgs({ package: 'requests>=2.25.1' })).not.toThrow();
  });

  it('rejects malicious command injection payloads in package names', () => {
    expect(() => installPackageTool.validateArgs({ package: 'axios; rm -rf /' })).toThrow(/potential command injection/i);
    expect(() => installPackageTool.validateArgs({ package: 'express && cat /etc/passwd' })).toThrow(/potential command injection/i);
    expect(() => installPackageTool.validateArgs({ package: 'pkg | whoami' })).toThrow(/potential command injection/i);
    expect(() => installPackageTool.validateArgs({ package: 'pkg `id`' })).toThrow(/potential command injection/i);
  });
});
