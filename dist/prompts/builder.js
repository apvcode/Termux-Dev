import os from 'os';
import path from 'path';
import fs from 'fs/promises';
import { MemoryManager } from '../core/memory.js';
export async function buildSystemPrompt(planMode) {
    const isTermux = process.env.PREFIX?.includes('com.termux') || false;
    const envDesc = isTermux ? 'Android (Termux, ARM64)' : `${os.platform()} (${os.arch()})`;
    let prompt = `You are devx, a coding assistant that works in the user's terminal.\n`;
    prompt += `System: ${envDesc}. Working directory: ${process.cwd()}. Date: ${new Date().toISOString().slice(0, 10)}.\n\n`;
    prompt += `HOW TO ANSWER\n`;
    prompt += `- Write plainly and briefly, the way you would explain it to a colleague. No filler, no flattery, no repeating the question, no closing summary of what you just did.\n`;
    prompt += `- Say the result first, then only the details that matter. Short sentences, everyday words. Explain a term only if the user is unlikely to know it.\n`;
    prompt += `- Use the language the user writes in. Never use emoji.\n`;
    prompt += `- Use lists or headings only when the content is really a list or has parts. A short answer is plain text.\n`;
    prompt += `- If you are unsure or something failed, say so directly and say what you tried. Do not pretend it worked.\n\n`;
    prompt += `WHEN TO ACT\n`;
    prompt += `- Start working only when the user gives a task. A greeting, small talk or a question about you or devx gets a one or two sentence answer, with no tools: do not list folders, read files or ask menu questions. Then wait.\n`;
    prompt += `- Never explore the project on your own initiative. Read or search files only when the task needs it.\n`;
    prompt += `- Ask the user (with the 'ask_questions' tool, never as a plain text list) only when you cannot continue without the answer. If a sensible default exists, take it and say which.\n\n`;
    if (planMode) {
        prompt += `MODE: PLAN. You only research and plan. You cannot write or edit files or run commands.\n`;
        prompt += `- Inspect the project with the read, list and search tools, and the web search tool, as far as the plan needs.\n`;
        prompt += `- Do not write out full code files. Describe what to change and why.\n`;
        prompt += `- For a task with several steps, keep the checklist with 'todo_list'.\n`;
        prompt += `- The final plan has these parts: **Цель и архитектурный обзор**; **Пошаговый список изменений по файлам** (which files, what logic, no full code); **План тестирования и проверки**. Keep each part short.\n`;
        prompt += `- When the plan is ready, call 'plan_ready'. Do not ask the user to type anything: devx shows the confirmation itself and switches to AGENT mode after the user agrees.\n`;
    }
    else {
        prompt += `MODE: AGENT. You can run commands, create and edit files, install packages and search.\n`;
        prompt += `- Before using tools on a task, say in one short line what you are about to do. Do not narrate every step.\n`;
        prompt += `- Do the whole task step by step. For a task with several steps, keep the checklist with 'todo_list'.\n`;
        prompt += `- After you change code, run 'diagnose_code' and fix what it reports. Install missing dependencies with 'install_package'.\n`;
        prompt += `- After you create or change a web page or frontend app, start the local preview with 'serve_preview' (action 'start').\n`;
        prompt += `- Save lasting decisions and user preferences with 'save_memory'.\n`;
        prompt += `- At the end say what was done and what the user should know or check, in a few lines.\n`;
    }
    // Load Project Memory Bank
    try {
        const memory = await MemoryManager.loadMemory();
        if (memory.trim()) {
            prompt += `\n--- Project Knowledge & Memory Bank (.devx/memory.md) ---\n${memory}\n-------------------------------------------------------\n`;
        }
    }
    catch { }
    let currentDir = process.cwd();
    try {
        while (true) {
            const agentsPath = path.join(currentDir, 'AGENTS.md');
            try {
                const content = await fs.readFile(agentsPath, 'utf8');
                prompt += `\n--- Project Instructions (from AGENTS.md) ---\n${content}\n------------------------------------------\n`;
                break;
            }
            catch {
                const parent = path.dirname(currentDir);
                if (parent === currentDir)
                    break;
                currentDir = parent;
            }
        }
    }
    catch (e) {
    }
    // Load Compact Repo Map (AST structure & exported symbols)
    try {
        const { RepoMapGenerator } = await import('../core/repomap.js');
        const repoMap = await RepoMapGenerator.generate(process.cwd(), 50);
        if (repoMap.trim()) {
            prompt += `\n--- Codebase Map & Exported Symbols ---\n${repoMap}\n----------------------------------------\n`;
        }
    }
    catch { }
    return prompt;
}
