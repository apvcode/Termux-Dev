import fs from 'fs/promises';
import fsSync from 'fs';
import path from 'path';

/**
 * The approved plan is kept in `.devx/plan.md` of the project, so it survives /clear and a restart
 * and the agent can follow it. Only a recent plan is given to the model (an old one is probably
 * about something else).
 */
export interface PlanData {
  summary: string;
  files?: string[];
  steps?: string[];
}

const planFile = (cwd: string) => path.join(cwd, '.devx', 'plan.md');

export const PLAN_FRESH_MS = 24 * 60 * 60 * 1000;
export const PLAN_PROMPT_MAX_CHARS = 3000;

export function planToMarkdown(plan: PlanData, when: Date = new Date()): string {
  const stamp = when.toISOString().slice(0, 16).replace('T', ' ');
  const lines = ['# Plan', '', `_Approved ${stamp}_`, '', plan.summary.trim(), ''];
  const files = (plan.files ?? []).filter(Boolean);
  if (files.length > 0) lines.push('## Files', '', ...files.map((f) => `- ${f}`), '');
  const steps = (plan.steps ?? []).filter(Boolean);
  if (steps.length > 0) lines.push('## Steps', '', ...steps.map((s, i) => `${i + 1}. ${s}`), '');
  return lines.join('\n');
}

/** Writes the plan (structured data, or the text of a plan the model wrote in its answer). Returns the path. */
export async function savePlanFile(cwd: string, plan: PlanData | string, when: Date = new Date()): Promise<string> {
  const markdown = typeof plan === 'string'
    ? `# Plan\n\n_Approved ${when.toISOString().slice(0, 16).replace('T', ' ')}_\n\n${plan.trim()}\n`
    : planToMarkdown(plan, when);
  const file = planFile(cwd);
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, markdown, 'utf8');
  return file;
}

/** The plan for the system prompt: only if it is recent, cut to a size that does not eat the context. */
export function loadPlanForPrompt(cwd: string, now: number = Date.now()): string {
  const file = planFile(cwd);
  try {
    const stat = fsSync.statSync(file);
    if (now - stat.mtimeMs > PLAN_FRESH_MS) return '';
    const text = fsSync.readFileSync(file, 'utf8').trim();
    if (text.length <= PLAN_PROMPT_MAX_CHARS) return text;
    return text.slice(0, PLAN_PROMPT_MAX_CHARS).replace(/\n[^\n]*$/, '') + '\n… (the rest is in .devx/plan.md)';
  } catch {
    return '';
  }
}
