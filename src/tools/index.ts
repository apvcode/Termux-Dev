import { readFileTool, writeFileTool, editFileTool, listDirTool, mkdirTool } from './fs.js';
import { bashTool } from './bash.js';
import { searchTool } from './search.js';
import { askQuestionsTool } from './questions.js';
import { webSearchTool, fetchUrlTool } from './web.js';
import { diagnoseCodeTool } from './diagnostics.js';
import { installPackageTool } from './packages.js';
import { saveMemoryTool } from '../core/memory.js';
import { planReadyTool, lastPlanReady, resetPlanReady } from './plan.js';
import { todoListTool, currentTodoList, resetTodoList } from './todo.js';
import { servePreviewTool } from './server.js';
import { Tool } from '../core/types.js';

import { MCPManager } from '../mcp/manager.js';

export function getTools(planMode: boolean): Tool[] {
  const baseTools = [readFileTool, listDirTool, searchTool, askQuestionsTool, webSearchTool, fetchUrlTool, saveMemoryTool, planReadyTool, todoListTool];
  const mcpTools = MCPManager.getInstance().getTools();
  
  if (planMode) {
    return [...baseTools, ...mcpTools];
  }
  
  return [...baseTools, writeFileTool, editFileTool, mkdirTool, bashTool, diagnoseCodeTool, installPackageTool, servePreviewTool, ...mcpTools];
}

export { webSearchTool, fetchUrlTool, diagnoseCodeTool, installPackageTool, saveMemoryTool, planReadyTool, lastPlanReady, resetPlanReady, todoListTool, currentTodoList, resetTodoList, servePreviewTool };


