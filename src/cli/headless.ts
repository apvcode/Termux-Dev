import pc from 'picocolors';
import { AgentConfig, ToolCall, DEVX_VERSION } from '../core/types.js';
import { History } from '../core/history.js';
import { Agent } from '../core/loop.js';
import { buildSystemPrompt } from '../prompts/builder.js';
import { createProvider } from '../providers/index.js';
import { getTools } from '../tools/index.js';
import { CLIConsoleGuard } from '../permissions/guard.js';
import { globalSnapshotManager } from '../core/snapshot.js';
import { UsageTracker } from '../core/usage.js';

export interface HeadlessOptions {
  planMode?: boolean;
  yolo?: boolean;
  quiet?: boolean;
  json?: boolean;
}

export async function runHeadlessMode(
  userPrompt: string,
  config: AgentConfig,
  options: HeadlessOptions = {}
): Promise<number> {
  const planMode = !!options.planMode;
  const isYolo = !!options.yolo || !!config.autoApprove;
  const isQuiet = !!options.quiet;
  const isJson = !!options.json;

  let history = new History();
  const sysPrompt = await buildSystemPrompt(planMode);
  history.updateSystemPrompt(sysPrompt);
  history.addMessage({ role: 'user', content: userPrompt });

  const provider = createProvider(config);
  const tools = getTools(planMode);
  const guard = new CLIConsoleGuard(isYolo);
  const agent = new Agent(config, provider, tools, history, guard);

  let executedTools: Array<{ id: string; name: string; result: string }> = [];
  let accumulatedText = '';
  let accumulatedReasoning = '';
  let isFatalError = false;
  let errorMessage = '';

  const abortController = new AbortController();
  process.on('SIGINT', () => {
    abortController.abort();
    if (!isJson) {
      console.error(pc.yellow('\n[devx headless] Aborted by user (SIGINT)'));
    }
    process.exit(130);
  });

  try {
    if (!isQuiet && !isJson) {
      console.log(pc.bold(pc.cyan(`⚡ devx v${DEVX_VERSION} (headless) | ${planMode ? 'PLAN' : 'AGENT'} | ${config.model}`)));
      console.log(pc.dim(`Task: ${userPrompt}\n`));
    }

    for await (const event of agent.run(abortController.signal)) {
      if (event.type === 'reasoning_delta') {
        accumulatedReasoning += event.delta;
        if (!isQuiet && !isJson) {
          process.stdout.write(pc.dim(event.delta));
        }
      } else if (event.type === 'text_delta') {
        accumulatedText += event.delta;
        if (!isJson) {
          process.stdout.write(event.delta);
        }
      } else if (event.type === 'tool_start') {
        if (!isQuiet && !isJson) {
          console.log('\n' + pc.bold(pc.white(`› ${event.actionDesc}`)));
        }
      } else if (event.type === 'tool_end') {
        executedTools.push({ id: event.id, name: event.name, result: event.result });
      } else if (event.type === 'error') {
        errorMessage = event.message;
        if (event.isFatal) {
          isFatalError = true;
          break;
        }
      }
    }

    if (isJson) {
      const summary = UsageTracker.getInstance().getSummary();
      const output = {
        success: !isFatalError,
        model: config.model,
        prompt: userPrompt,
        result: accumulatedText,
        reasoning: accumulatedReasoning || undefined,
        executedTools,
        usage: {
          requests: summary.requestsCount,
          totalTokens: summary.totalTokens,
          cost: summary.totalCost,
          totalBytes: summary.totalBytes
        },
        error: isFatalError ? errorMessage : undefined
      };
      console.log(JSON.stringify(output, null, 2));
    } else {
      if (accumulatedText && !accumulatedText.endsWith('\n')) {
        process.stdout.write('\n');
      }
      if (isFatalError) {
        console.error(pc.red(`\n❌ Error: ${errorMessage}`));
        return 1;
      }
    }

    return isFatalError ? 1 : 0;
  } catch (err: any) {
    if (isJson) {
      console.log(JSON.stringify({ success: false, error: err.message }, null, 2));
    } else {
      console.error(pc.red(`\n❌ Fatal Headless Execution Error: ${err.message}`));
    }
    return 1;
  } finally {
    globalSnapshotManager.finishTurn();
  }
}
