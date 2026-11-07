import { Tool } from '../core/types.js';
import pc from 'picocolors';

export interface PlanReadyData {
  summary: string;
  files?: string[];
  steps?: string[];
  timestamp: number;
}

export let lastPlanReady: PlanReadyData | null = null;

export function resetPlanReady() {
  lastPlanReady = null;
}

/**
 * Weaker models send `steps` / `files` as one string (numbered lines, a JSON array in text) or put
 * other things in the list. Accept those forms so the plan card is never lost to a shape mismatch.
 */
export function toStringList(value: unknown): string[] {
  let items: unknown[];
  if (Array.isArray(value)) {
    items = value;
  } else if (typeof value === 'string') {
    const text = value.trim();
    if (!text) return [];
    let parsed: unknown;
    if (text.startsWith('[')) {
      try { parsed = JSON.parse(text); } catch {}
    }
    items = Array.isArray(parsed) ? parsed : text.split('\n').map((line) => line.replace(/^\s*(?:\d+[.)]|[-*•])\s+/, ''));
  } else {
    return [];
  }
  return items
    .map((item) => (typeof item === 'string' ? item : item == null ? '' : typeof item === 'object' ? JSON.stringify(item) : String(item)).trim())
    .filter(Boolean);
}

export const planReadyTool: Tool = {
  name: 'plan_ready',
  definition: {
    name: 'plan_ready',
    description: 'Signal that the plan is finalized and ready for user approval and execution.',
    parameters: {
      type: 'object',
      properties: {
        summary: {
          type: 'string',
          description: 'A brief 1-2 sentence summary of what will be built or modified.'
        },
        files: {
          type: 'array',
          items: { type: 'string' },
          description: 'List of file paths that will be created or edited.'
        },
        steps: {
          type: 'array',
          items: { type: 'string' },
          description: 'Step-by-step implementation roadmap.'
        }
      },
      required: ['summary']
    }
  },
  validateArgs: (args: any) => {
    if (!args || typeof args !== 'object') {
      throw new Error('Arguments must be an object');
    }
    // Without a summary nothing can be drawn: say so, so the model calls it again instead of
    // believing the plan was shown.
    if (typeof args.summary !== 'string' || !args.summary.trim()) {
      throw new Error("'summary' is required: a string with a 1-2 sentence summary of the plan. Call plan_ready again with 'summary', 'files' (array of strings) and 'steps' (array of strings).");
    }
  },
  execute: async (args: { summary: string; files?: unknown; steps?: unknown }) => {
    const summary = args.summary.trim();
    const files = toStringList(args.files);
    const steps = toStringList(args.steps);
    lastPlanReady = {
      summary,
      files: files.length > 0 ? files : undefined,
      steps: steps.length > 0 ? steps : undefined,
      timestamp: Date.now()
    };

    // JSON: devx draws it as the plan card; the model reads the same data.
    return JSON.stringify({
      type: 'plan_ready',
      status: 'shown to the user, who approves it or asks for changes',
      summary,
      ...(files.length > 0 ? { files } : {}),
      ...(steps.length > 0 ? { steps } : {}),
    });
  }
};
