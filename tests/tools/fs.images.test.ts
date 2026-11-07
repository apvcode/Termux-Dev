import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs/promises';
import path from 'path';
import os from 'os';
import { readFileTool } from '../../src/tools/fs.js';
import { IMAGE_WARN_BYTES } from '../../src/core/images.js';
import { VisionCatalog, setVisionCatalogs } from '../../src/core/vision.js';
import { PNG_1X1 } from '../helpers/png.js';

describe('read_file on an image', () => {
  let dir: string;
  let shown: Array<{ path: string; dataUrl: string }>;
  const context = () => ({ attachImage: (image: { path: string; dataUrl: string }) => shown.push(image) });

  beforeEach(async () => {
    dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-img-'));
    shown = [];
  });
  afterEach(async () => {
    await fs.rm(dir, { recursive: true, force: true });
  });

  it('shows the picture to the model and says so in the result', async () => {
    const file = path.join(dir, 'shot.png');
    await fs.writeFile(file, PNG_1X1);
    const result = await readFileTool.execute({ path: file }, { model: 'some-unknown-model' }, context());
    expect(shown).toHaveLength(1);
    expect(shown[0].path).toBe(file);
    expect(shown[0].dataUrl).toBe(`data:image/png;base64,${PNG_1X1.toString('base64')}`);
    expect(result).toContain('attached');
    expect(result).toContain('image/png');
  });

  it('takes the type from the bytes, not from a wrong extension', async () => {
    const file = path.join(dir, 'really-a-png.jpg');
    await fs.writeFile(file, PNG_1X1);
    await readFileTool.execute({ path: file }, {}, context());
    expect(shown[0].dataUrl.startsWith('data:image/png;base64,')).toBe(true);
  });

  it('refuses a file that only has the name of an image (it would break every later request)', async () => {
    const file = path.join(dir, 'fake.png');
    await fs.writeFile(file, 'this is just text');
    await expect(readFileTool.execute({ path: file }, {}, context())).rejects.toThrow(/not a valid/);
    expect(shown).toHaveLength(0);
  });

  it('refuses a picture above the size the strictest API takes, and tells how to shrink it', async () => {
    const file = path.join(dir, 'huge.png');
    await fs.writeFile(file, Buffer.concat([PNG_1X1, Buffer.alloc(IMAGE_WARN_BYTES)]));
    await expect(readFileTool.execute({ path: file }, {}, context())).rejects.toThrow(/too large.*smaller copy/s);
    expect(shown).toHaveLength(0);
  });

  it('does not send a picture to a model that cannot read images, and says why', async () => {
    const file = path.join(dir, 'shot.png');
    await fs.writeFile(file, PNG_1X1);
    setVisionCatalogs(new VisionCatalog({ version: 1, generated: 'test', yes: ['sees-model'], no: ['blind-model'] }));
    try {
      const blind = await readFileTool.execute({ path: file }, { model: 'blind-model' }, context());
      expect(shown).toHaveLength(0);
      expect(blind).toContain('cannot read images');
      await readFileTool.execute({ path: file }, { model: 'sees-model' }, context());
      expect(shown).toHaveLength(1);
    } finally {
      setVisionCatalogs(null);
    }
  });

  it('says so when it has no way to show a picture, instead of dumping bytes', async () => {
    const file = path.join(dir, 'shot.png');
    await fs.writeFile(file, PNG_1X1);
    const result = await readFileTool.execute({ path: file }, {});
    expect(result).toContain('cannot be shown');
    expect(result).not.toContain('PNG');
  });

  it('still reads SVG as text', async () => {
    const file = path.join(dir, 'logo.svg');
    await fs.writeFile(file, '<svg xmlns="http://www.w3.org/2000/svg"></svg>');
    const result = await readFileTool.execute({ path: file }, {}, context());
    expect(result).toContain('<svg');
    expect(shown).toHaveLength(0);
  });
});
