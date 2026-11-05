import fs from 'fs/promises';

/**
 * Writes a file that may contain secrets (API keys, conversation transcripts).
 * New files are created with mode 0600 and existing files are tightened to 0600,
 * so credentials are never left readable by other local users.
 * On platforms without POSIX permissions (Windows) the chmod is a harmless no-op.
 */
export async function writeSecureFile(filePath: string, data: string): Promise<void> {
  await fs.writeFile(filePath, data, { encoding: 'utf8', mode: 0o600 });
  try {
    await fs.chmod(filePath, 0o600);
  } catch {}
}

/** Creates a private (0700) directory, tightening it if it already exists. */
export async function ensureSecureDir(dirPath: string): Promise<void> {
  await fs.mkdir(dirPath, { recursive: true, mode: 0o700 });
  try {
    await fs.chmod(dirPath, 0o700);
  } catch {}
}
