import { SessionRuntime, RtEvent } from './runtime.js';
import type { LLMProvider } from '../core/types.js';

/** What a session worker can be asked to do. The same code serves the child process and the tests. */
export function createWorkerApi(io: {
  emit: (event: RtEvent) => void;
  rpc: (method: string, params: any) => Promise<any>;
  providerFactory?: (config: any, light?: boolean) => LLMProvider;
}) {
  let runtime: SessionRuntime | null = null;

  async function call(m: string, p: any): Promise<any> {
    if (m === 'init') {
      runtime = new SessionRuntime({ cwd: p.cwd, sessionId: p.sessionId, planMode: p.planMode, emit: io.emit, rpc: io.rpc, providerFactory: io.providerFactory });
      await runtime.init();
      return { id: runtime.sessionId() };
    }
    const rt = runtime;
    if (!rt) throw new Error('not started');
    switch (m) {
      case 'snapshot': return rt.snapshot(p?.limit);
      case 'state': return rt.state();
      case 'send': return rt.send(p.text, p.files ?? []);
      case 'stop': rt.stop(); return {};
      case 'perm': return { ok: rt.answerPermission(p.id, p.answer) };
      case 'ask': return { ok: rt.answerQuestions(p.id, p.answers) };
      case 'plan': return { ok: rt.decidePlan(p.id, p.choice) };
      case 'profile': rt.setProfile(p); return {};
      case 'mode': await rt.setMode(p); return {};
      case 'model': await rt.setModel(p.provider, p.model); return {};
      case 'effort': return rt.setEffort(p.level);
      case 'trust': await rt.trustFolder(); return {};
      case 'rename': await rt.rename(p.title); return {};
      case 'reset': await rt.reset(); return { id: rt.sessionId() };
      case 'compact': return rt.compact();
      case 'undo': return rt.undo();
      case 'info': return rt.info();
      case 'tasks': return rt.tasks();
      case 'stopTask': return { ok: await rt.stopTask(p.id) };
      case 'mcp': return rt.mcp(String(p.args ?? ''));
      case 'commands': return rt.commandList();
      case 'expand': return { text: await rt.expandCustom(p.line) };
      case 'busy': return { running: rt.isRunning(), background: rt.hasBackgroundWork() };
      case 'close': await rt.close(); return {};
      default: throw new Error(`unknown call ${m}`);
    }
  }
  return { call };
}
