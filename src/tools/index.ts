import { readFileTool, writeFileTool, editFileTool, listDirTool, mkdirTool } from './fs.js';
import { bashTool } from './bash.js';
import { backgroundTool } from './background.js';
import { searchTool } from './search.js';
import { askQuestionsTool } from './questions.js';
import { webSearchTool, fetchUrlTool } from './web.js';
import { diagnoseCodeTool } from './diagnostics.js';
import { installPackageTool } from './packages.js';
import { saveMemoryTool } from '../core/memory.js';
import { planReadyTool, lastPlanReady, resetPlanReady } from './plan.js';
import { todoListTool, currentTodoList, resetTodoList } from './todo.js';
import { servePreviewTool } from './server.js';
import { mcpManageTool } from './mcp.js';
import { createMcpSearchTool } from './mcp-search.js';
import { Tool, ToolSource } from '../core/types.js';

import { MCPManager } from '../mcp/manager.js';

export function getTools(planMode: boolean): Tool[] {
  const baseTools = [readFileTool, listDirTool, searchTool, askQuestionsTool, webSearchTool, fetchUrlTool, saveMemoryTool, planReadyTool, todoListTool];
  const manager = MCPManager.getInstance();
  // The tools of the big servers are not sent until the agent loads them; `mcp_search` is how it does.
  const mcpTools = [...(manager.hasLazy() ? [createMcpSearchTool(manager)] : []), ...manager.getTools()];

  if (planMode) {
    return [...baseTools, ...mcpTools];
  }

  return [...baseTools, writeFileTool, editFileTool, mkdirTool, bashTool, backgroundTool, diagnoseCodeTool, installPackageTool, servePreviewTool, mcpManageTool, ...mcpTools];
}

/** The tools for an agent run: asked again before every request, so what the agent loads is there at its next step. */
export function getToolSource(planMode: boolean): ToolSource {
  return {
    current: () => getTools(planMode),
    explainMissing: (name) => MCPManager.getInstance().loadOnMiss(name),
  };
}

export { webSearchTool, fetchUrlTool, diagnoseCodeTool, installPackageTool, saveMemoryTool, planReadyTool, lastPlanReady, resetPlanReady, todoListTool, currentTodoList, resetTodoList, servePreviewTool };


