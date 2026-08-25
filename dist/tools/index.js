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
export function getTools(planMode) {
    const baseTools = [readFileTool, listDirTool, searchTool, askQuestionsTool, webSearchTool, fetchUrlTool, saveMemoryTool, planReadyTool, todoListTool];
    if (planMode) {
        return baseTools;
    }
    return [...baseTools, writeFileTool, editFileTool, mkdirTool, bashTool, diagnoseCodeTool, installPackageTool];
}
export { webSearchTool, fetchUrlTool, diagnoseCodeTool, installPackageTool, saveMemoryTool, planReadyTool, lastPlanReady, resetPlanReady, todoListTool, currentTodoList, resetTodoList };
