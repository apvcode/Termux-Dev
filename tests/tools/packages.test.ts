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

  it('rejects command line options disguised as package names', () => {
    expect(() => installPackageTool.validateArgs({ package: '--registry=http://evil.example/ express' })).toThrow(/options are not allowed/i);
    expect(() => installPackageTool.validateArgs({ package: 'express -g' })).toThrow(/options are not allowed/i);
    expect(() => installPackageTool.validateArgs({ package: '--index-url=http://evil.example/simple requests' })).toThrow(/options are not allowed/i);
    expect(() => installPackageTool.validateArgs({ package: '-r requirements.txt' })).toThrow(/options are not allowed/i);
    // hyphens inside a name are fine
    expect(() => installPackageTool.validateArgs({ package: 'react-dom lodash-es @scope/my-pkg' })).not.toThrow();
  });

  it('rejects an unknown package manager', () => {
    expect(() => installPackageTool.validateArgs({ package: 'express', manager: 'apt' })).toThrow(/Unsupported package manager/);
    expect(() => installPackageTool.validateArgs({ package: 'express', manager: 'pnpm' })).not.toThrow();
  });
});
