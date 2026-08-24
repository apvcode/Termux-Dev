import fs from 'fs/promises';
import fsSync from 'fs';
import path from 'path';
import os from 'os';
import { exec } from 'child_process';

const MEDIA_DIR = path.join(process.cwd(), '.devx', 'media');

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}b`;
  const kb = Math.round(bytes / 1024);
  if (kb < 1024) return `${kb}kb`;
  const mb = (bytes / (1024 * 1024)).toFixed(1);
  return `${mb}mb`;
}

export function getUniqueImageName(baseName: string, existingNames: Set<string>): string {
  if (!existingNames.has(baseName)) {
    return baseName;
  }

  const ext = path.extname(baseName);
  const nameWithoutExt = path.basename(baseName, ext);

  let copyIndex = 1;
  while (true) {
    const candidate = copyIndex === 1
      ? `${nameWithoutExt}(copy)${ext}`
      : `${nameWithoutExt}(copy ${copyIndex})${ext}`;

    if (!existingNames.has(candidate)) {
      return candidate;
    }
    copyIndex++;
  }
}

export async function saveClipboardImage(preferredName = 'image.png', existingNames: Set<string>): Promise<{ fileName: string; filePath: string; size: number; sizeStr: string } | null> {
  if (!fsSync.existsSync(MEDIA_DIR)) {
    await fs.mkdir(MEDIA_DIR, { recursive: true });
  }

  const uniqueName = getUniqueImageName(preferredName, existingNames);
  const targetPath = path.join(MEDIA_DIR, uniqueName);

  return new Promise((resolve) => {
    const isWindows = os.platform() === 'win32';
    const isMac = os.platform() === 'darwin';

    if (isWindows) {
      const psScript = `
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$img = [System.Windows.Forms.Clipboard]::GetImage()
if ($img -ne $null) {
    $img.Save('${targetPath.replace(/\\/g, '\\\\')}', [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Output "SAVED"
} else {
    Write-Output "NO_IMAGE"
}
`;
      const b64 = Buffer.from(psScript, 'utf16le').toString('base64');
      exec(`powershell.exe -NoProfile -NonInteractive -EncodedCommand ${b64}`, (err, stdout) => {
        if (!err && stdout.includes('SAVED') && fsSync.existsSync(targetPath)) {
          const stat = fsSync.statSync(targetPath);
          resolve({
            fileName: uniqueName,
            filePath: targetPath,
            size: stat.size,
            sizeStr: formatFileSize(stat.size)
          });
        } else {
          resolve(null);
        }
      });
    } else if (isMac) {
      exec(`pngpaste "${targetPath}"`, (err) => {
        if (!err && fsSync.existsSync(targetPath)) {
          const stat = fsSync.statSync(targetPath);
          resolve({
            fileName: uniqueName,
            filePath: targetPath,
            size: stat.size,
            sizeStr: formatFileSize(stat.size)
          });
        } else {
          resolve(null);
        }
      });
    } else {
      // Linux / Termux: try xclip / wl-paste
      exec(`xclip -selection clipboard -t image/png -o > "${targetPath}" 2>/dev/null || wl-paste -t image/png > "${targetPath}" 2>/dev/null`, () => {
        if (fsSync.existsSync(targetPath)) {
          const stat = fsSync.statSync(targetPath);
          if (stat.size > 100) {
            return resolve({
              fileName: uniqueName,
              filePath: targetPath,
              size: stat.size,
              sizeStr: formatFileSize(stat.size)
            });
          }
          try { fsSync.unlinkSync(targetPath); } catch {}
        }
        resolve(null);
      });
    }
  });
}

export async function processPastedFilePath(rawPath: string, existingNames: Set<string>): Promise<{ fileName: string; filePath: string; size: number; sizeStr: string } | null> {
  const cleanPath = rawPath.trim().replace(/^['"&]+|['"]+$/g, '').trim();
  const resolved = path.resolve(process.cwd(), cleanPath);

  const imageExts = new Set(['.png', '.jpg', '.jpeg', '.webp', '.gif', '.svg', '.bmp']);
  const ext = path.extname(resolved).toLowerCase();

  if (imageExts.has(ext) && fsSync.existsSync(resolved)) {
    try {
      const stat = fsSync.statSync(resolved);
      if (stat.isFile()) {
        const baseName = path.basename(resolved);
        const uniqueName = getUniqueImageName(baseName, existingNames);

        if (!fsSync.existsSync(MEDIA_DIR)) {
          await fs.mkdir(MEDIA_DIR, { recursive: true });
        }

        const targetPath = path.join(MEDIA_DIR, uniqueName);
        await fs.copyFile(resolved, targetPath);

        return {
          fileName: uniqueName,
          filePath: targetPath,
          size: stat.size,
          sizeStr: formatFileSize(stat.size)
        };
      }
    } catch {}
  }

  return null;
}
