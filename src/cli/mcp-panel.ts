import { LiveBlock } from './liveblock.js';
import { normalizeKey, splitKeys } from './keys.js';
import { PermissionInput, PermissionOutput } from './permission.js';
import { palette } from './ui.js';
import { displayWidth, padEndWidth, truncateWidth, wrapText } from './width.js';
import { t } from '../core/i18n.js';
import type { MCPServerStatus, MCPToolInfo } from '../mcp/types.js';

/**
 * The /mcp panel: the servers, one server, its tools, one tool. One card that is redrawn in
 * place (like the settings), with the arrow keys, Enter to go in and Esc to go back.
 *
 * Rendering is a pure function of `PanelView`; `runMcpPanel` adds the keyboard and the
 * actions (reconnect, switch on and off, reload) of the MCP manager.
 */

export type Screen =
  | { kind: 'servers' }
  | { kind: 'server'; name: string }
  | { kind: 'tools'; name: string }
  | { kind: 'tool'; name: string; tool: string };

export interface PanelView {
  statuses: MCPServerStatus[];
  screen: Screen;
  /** The highlighted row of the current screen. */
  focus: number;
  /** Something is running (a server connects): shown in the place of the hints. */
  busy?: boolean;
}

export type ServerAction = 'tools' | 'reconnect' | 'disable' | 'enable';

/** What can be done with a server in its current state, in the order shown. */
export function actionsFor(status: MCPServerStatus): ServerAction[] {
  if (status.status === 'disabled') return ['enable'];
  const list: ServerAction[] = [];
  if (status.status === 'connected' && status.toolsCount > 0) list.push('tools');
  list.push('reconnect', 'disable');
  return list;
}

const actionLabel = (a: ServerAction) => t(`mcp.action.${a}` as const);

/** The servers in the order of the list: the user's own first, then the project's. */
export function orderedServers(statuses: MCPServerStatus[]): MCPServerStatus[] {
  return [...statuses.filter((s) => s.scope !== 'project'), ...statuses.filter((s) => s.scope === 'project')];
}

/** How many rows the current screen can focus. */
export function rowCount(view: PanelView): number {
  const { screen } = view;
  const status = (name: string) => view.statuses.find((s) => s.name === name);
  switch (screen.kind) {
    case 'servers':
      return view.statuses.length;
    case 'server': {
      const s = status(screen.name);
      return s ? actionsFor(s).length : 0;
    }
    case 'tools':
      return status(screen.name)?.toolInfo?.length ?? 0;
    default:
      return 0;
  }
}

// ───────────────────────── drawing ─────────────────────────

interface Row {
  text: string;
  /** The highlighted row. */
  shaded?: boolean;
  /** The row the window keeps in view. */
  anchor?: boolean;
}

function statusGlyph(s: MCPServerStatus): string {
  switch (s.status) {
    case 'connected': return palette.ok('●');
    case 'connecting': return palette.warn('◌');
    case 'failed': return palette.err('×');
    default: return palette.faint('○');
  }
}

function statusText(s: MCPServerStatus): string {
  switch (s.status) {
    case 'connected': return palette.ok(t('mcp.status.connected'));
    case 'connecting': return palette.warn(t('mcp.status.connecting'));
    case 'failed': return palette.err(t('mcp.status.failed'));
    default: return palette.faint(t('mcp.status.disabled'));
  }
}

function toolTags(tool: MCPToolInfo): string {
  const tags: string[] = [];
  if (tool.readOnly) tags.push(t('mcp.tag.readOnly'));
  if (tool.destructive) tags.push(t('mcp.tag.destructive'));
  if (tool.openWorld) tags.push(t('mcp.tag.openWorld'));
  return tags.join(' · ');
}

/** Shows `max` rows of a long list, always including the anchor, with "↑ n more" / "↓ n more" at the cut edges. */
function windowRows(rows: Row[], max: number, width: number): Row[] {
  if (rows.length <= max) return rows;
  const anchor = Math.max(0, rows.findIndex((r) => r.anchor));
  let start = Math.max(0, Math.min(rows.length - max, anchor - Math.floor(max / 2)));
  const end = start + max;
  const out = rows.slice(start, end);
  const above = start;
  const below = rows.length - end;
  const marker = (arrow: string, n: number) => ({ text: ' ' + palette.faint(truncateWidth(`${arrow} ${t('mcp.more', { n })}`, width)) });
  if (above > 0) out[0] = marker('↑', above + 1);
  if (below > 0) out[out.length - 1] = marker('↓', below + 1);
  // The anchor must survive the markers.
  if (!out.some((r) => r.anchor) && rows[anchor]) out[Math.min(out.length - 1, Math.max(1, anchor - start))] = rows[anchor];
  return out;
}

function hintsFor(view: PanelView, width: number): string {
  if (view.busy) return palette.warn(truncateWidth(t('mcp.status.connecting'), width));
  const move = ['↑↓', t('settings.keys.move')] as [string, string];
  const open = ['enter', t('mcp.keys.open')] as [string, string];
  const back = ['esc', t('mcp.keys.back')] as [string, string];
  const close = ['esc', t('mcp.keys.close')] as [string, string];
  const reload = ['r', t('mcp.keys.reload')] as [string, string];
  const variants: Array<Array<[string, string]>> =
    view.screen.kind === 'servers'
      ? [[move, open, reload, close], [move, open, close], [['↑↓', ''], ['enter', ''], ['esc', '']], [['esc', '']]]
      : view.screen.kind === 'tool'
        ? [[back], [['esc', '']]]
        : [[move, open, back], [['↑↓', ''], ['enter', ''], ['esc', '']], [['esc', '']]];
  for (const items of variants) {
    const text = items.map(([k, label]) => palette.text(k) + (label ? ' ' + palette.faint(label) : '')).join(palette.faint(items[0][1] ? ' · ' : ' '));
    if (displayWidth(text) <= width) return text;
  }
  return '';
}

function serversBody(view: PanelView, iw: number): Row[] {
  const rows: Row[] = [];
  const servers = orderedServers(view.statuses);
  if (servers.length === 0) {
    rows.push({ text: ' ' + palette.muted(t('mcp.empty')) });
    for (const line of wrapText(t('mcp.emptyHint'), iw - 3)) rows.push({ text: '   ' + palette.faint(line) });
    return rows;
  }
  const nameWidth = Math.min(24, Math.max(8, ...servers.map((s) => displayWidth(s.name))));
  let scope: string | undefined;
  servers.forEach((s, i) => {
    const sc = s.scope === 'project' ? 'project' : 'user';
    if (sc !== scope) {
      if (scope !== undefined) rows.push({ text: '' });
      scope = sc;
      rows.push({ text: ' ' + palette.faint(t(sc === 'project' ? 'mcp.scope.project' : 'mcp.scope.user')) });
    }
    const focused = i === view.focus;
    const mark = focused ? palette.accent('❯') : ' ';
    const name = focused ? palette.bold(palette.text(truncateWidth(s.name, nameWidth))) : palette.muted(truncateWidth(s.name, nameWidth));
    const right =
      s.status === 'connected' ? palette.muted(t('mcp.toolsCount', { n: s.toolsCount }))
      : s.status === 'connecting' ? palette.warn(t('mcp.status.connecting'))
      : s.status === 'failed' ? palette.err(t('mcp.status.failed'))
      : palette.faint(t('mcp.status.disabled'));
    const left = ` ${mark} ${statusGlyph(s)} ${padEndWidth(name, nameWidth)}`;
    const gap = Math.max(2, iw - displayWidth(left) - displayWidth(right) - 1);
    rows.push({ text: left + ' '.repeat(gap) + right, shaded: focused, anchor: focused });
  });
  return rows;
}

function serverBody(view: PanelView, name: string, iw: number): Row[] {
  const s = view.statuses.find((x) => x.name === name);
  if (!s) return [];
  const rows: Row[] = [];
  const labels = ['status', 'command', 'config', 'capabilities', 'tools', 'error'] as const;
  const labelW = Math.min(16, Math.max(...labels.map((l) => displayWidth(t(`mcp.detail.${l}` as const) + ':'))) + 1);
  const field = (label: (typeof labels)[number], value: string, plainValue: string) => {
    const head = ' ' + palette.faint(padEndWidth(t(`mcp.detail.${label}` as const) + ':', labelW));
    const room = Math.max(8, iw - 1 - labelW - 1);
    const lines = wrapText(plainValue, room);
    rows.push({ text: head + (lines.length <= 1 ? value : lines[0]) });
    if (lines.length > 1) for (const l of lines.slice(1, 3)) rows.push({ text: ' ' + ' '.repeat(labelW) + l });
  };
  const connecting = s.status === 'connecting';
  rows.push({ text: ' ' + palette.faint(padEndWidth(t('mcp.detail.status') + ':', labelW)) + statusGlyph(s) + ' ' + statusText(s) });
  field('command', palette.muted(truncateWidth(s.command, iw - 1 - labelW - 1)), s.command);
  if (s.source) field('config', palette.muted(s.source), s.source);
  if (s.capabilities && s.capabilities.length > 0) field('capabilities', palette.text(s.capabilities.join(' · ')), s.capabilities.join(' · '));
  if (s.status === 'connected') field('tools', palette.muted(String(s.toolsCount)), String(s.toolsCount));
  if (s.error) {
    const lines = wrapText(s.error, Math.max(8, iw - 1 - labelW - 1)).slice(0, 3);
    rows.push({ text: ' ' + palette.faint(padEndWidth(t('mcp.detail.error') + ':', labelW)) + palette.err(lines[0] ?? '') });
    for (const l of lines.slice(1)) rows.push({ text: ' ' + ' '.repeat(labelW) + palette.err(l) });
  }
  rows.push({ text: '' });
  if (!connecting) {
    actionsFor(s).forEach((a, i) => {
      const focused = i === view.focus;
      const mark = focused ? palette.accent('❯') : ' ';
      const label = `${i + 1}. ${actionLabel(a)}`;
      rows.push({ text: ` ${mark} ${focused ? palette.bold(palette.text(label)) : palette.muted(label)}`, shaded: focused, anchor: focused });
    });
  }
  return rows;
}

function toolsBody(view: PanelView, name: string, iw: number): Row[] {
  const s = view.statuses.find((x) => x.name === name);
  const tools = s?.toolInfo ?? [];
  if (tools.length === 0) return [{ text: ' ' + palette.muted(t('mcp.noTools')) }];
  const nameWidth = Math.min(Math.floor(iw * 0.6), Math.max(...tools.map((x) => displayWidth(x.name))) + 1);
  return tools.map((tool, i) => {
    const focused = i === view.focus;
    const mark = focused ? palette.accent('❯') : ' ';
    const left = ` ${mark} ${padEndWidth(focused ? palette.bold(palette.text(truncateWidth(tool.name, nameWidth))) : palette.muted(truncateWidth(tool.name, nameWidth)), nameWidth)}`;
    const room = iw - displayWidth(left) - 1;
    const tags = room > 6 ? palette.faint(truncateWidth(toolTags(tool), room)) : '';
    return { text: left + ' ' + tags, shaded: focused, anchor: focused };
  });
}

function toolBody(view: PanelView, name: string, toolName: string, iw: number): Row[] {
  const tool = view.statuses.find((x) => x.name === name)?.toolInfo?.find((x) => x.name === toolName);
  if (!tool) return [];
  const rows: Row[] = [];
  const tags = toolTags(tool);
  if (tags) rows.push({ text: ' ' + palette.faint(tags) });
  const desc = tool.description.replace(/\s+/g, ' ').trim();
  if (desc) {
    const lines = wrapText(desc, iw - 2);
    for (const l of lines.slice(0, 6)) rows.push({ text: ' ' + palette.text(l) });
    if (lines.length > 6) rows.push({ text: ' ' + palette.faint('…') });
  }
  rows.push({ text: '' });
  rows.push({ text: ' ' + palette.faint(t('mcp.params')) });
  if (tool.params.length === 0) rows.push({ text: '   ' + palette.muted(t('mcp.noParams')) });
  const nameW = Math.min(24, Math.max(4, ...tool.params.map((p) => displayWidth(p.name))));
  for (const p of tool.params) {
    const req = p.required ? palette.warn(' ' + t('mcp.required')) : '';
    const left = '   ' + palette.text(padEndWidth(truncateWidth(p.name, nameW), nameW)) + '  ' + palette.muted(truncateWidth(p.type, 14));
    rows.push({ text: left + req });
    const d = p.description.replace(/\s+/g, ' ').trim();
    if (d) rows.push({ text: '     ' + palette.faint(truncateWidth(d, Math.max(8, iw - 7))) });
  }
  return rows;
}

function titleFor(view: PanelView): { title: string; subtitle: string } {
  const { screen, statuses } = view;
  const s = 'name' in screen ? statuses.find((x) => x.name === screen.name) : undefined;
  switch (screen.kind) {
    case 'servers':
      return { title: t('mcp.title'), subtitle: t('mcp.count', { n: statuses.length }) };
    case 'server':
      return { title: screen.name, subtitle: s ? t(s.scope === 'project' ? 'mcp.scope.project' : 'mcp.scope.user') : '' };
    case 'tools':
      return { title: t('mcp.toolsTitle', { name: screen.name }), subtitle: s ? t('mcp.toolsCount', { n: s.toolInfo?.length ?? 0 }) : '' };
    default:
      return { title: screen.tool, subtitle: screen.name };
  }
}

/** The whole panel as terminal rows: a framed card, or a plain list on a narrow screen. */
export function renderMcpPanel(view: PanelView, cols: number, termRows = 30): string[] {
  const compact = cols < 60;
  const W = compact ? Math.max(20, cols - 1) : Math.min(cols - 1, 76);
  const iw = compact ? W - 1 : W - 4;
  const { screen } = view;

  let body: Row[];
  switch (screen.kind) {
    case 'servers': body = serversBody(view, iw); break;
    case 'server': body = serverBody(view, screen.name, iw); break;
    case 'tools': body = toolsBody(view, screen.name, iw); break;
    default: body = toolBody(view, screen.name, screen.tool, iw);
  }
  // The frame, the blank rows and the hints take 6 rows of the screen; the rest is for the list.
  const room = Math.max(3, termRows - (compact ? 3 : 6));
  body = windowRows(body, room, iw);
  if (body.length > room) body = body.slice(0, room);

  const { title, subtitle } = titleFor(view);
  const hints = hintsFor(view, cols - 2);

  if (compact) {
    const head = ' ' + palette.bold(palette.accent(truncateWidth(title, W - 2))) + (subtitle ? palette.faint(' · ' + truncateWidth(subtitle, Math.max(4, W - displayWidth(title) - 6))) : '');
    return [head, ...body.map((r) => (r.shaded ? palette.shade(padEndWidth(r.text, W)) : r.text)), ' ' + hints];
  }

  const rule = palette.rule;
  const box = (content: string, shaded = false) =>
    rule('│') + (shaded ? palette.shade(' ' + padEndWidth(content, iw) + ' ') : ' ' + padEndWidth(content, iw) + ' ') + rule('│');
  const titleText = ' ' + palette.bold(palette.accent(truncateWidth(title, W - 14))) + ' ';
  const subText = subtitle ? ' ' + palette.muted(truncateWidth(subtitle, Math.max(4, W - 8 - displayWidth(titleText)))) + ' ' : '';
  const fill = Math.max(0, W - 4 - displayWidth(titleText) - displayWidth(subText));
  return [
    rule('╭─') + titleText + rule('─'.repeat(fill)) + subText + rule('─╮'),
    box(''),
    ...body.map((r) => box(r.text, r.shaded)),
    box(''),
    rule('╰' + '─'.repeat(W - 2) + '╯'),
    ' ' + hints,
  ];
}

// ───────────────────────── keyboard ─────────────────────────

/** What the panel needs from the MCP manager. */
export interface McpController {
  getStatuses(): MCPServerStatus[];
  reconnect(name: string): Promise<unknown>;
  setEnabled(name: string, enabled: boolean): Promise<unknown>;
  reload(): Promise<unknown>;
}

export interface McpPanelOptions {
  io?: { input: PermissionInput; output: PermissionOutput & { rows?: number } };
}

/** Shows the panel and resolves when it is closed (Esc on the first screen, q, Ctrl+C). The panel is erased on close. */
export function runMcpPanel(controller: McpController, options: McpPanelOptions = {}): Promise<void> {
  return new Promise((resolve) => {
    const { input, output } = options.io ?? { input: process.stdin, output: process.stdout };
    const block = new LiveBlock(output);
    let stack: Screen[] = [{ kind: 'servers' }];
    const focusByScreen = new Map<string, number>();
    let busy = false;
    let closed = false;

    const screen = () => stack[stack.length - 1];
    const screenKey = () => JSON.stringify(screen());
    const view = (): PanelView => ({ statuses: controller.getStatuses(), screen: screen(), focus: focusByScreen.get(screenKey()) ?? 0, busy });
    const draw = () => {
      if (closed) return;
      // A server that is gone after a reload: back to the list.
      while (stack.length > 1) {
        const top = screen();
        if ('name' in top && !controller.getStatuses().some((x) => x.name === top.name)) stack.pop();
        else break;
      }
      const v = view();
      // Statuses change while a server connects: keep the focus inside the list.
      const max = Math.max(0, rowCount(v) - 1);
      if (v.focus > max) {
        focusByScreen.set(screenKey(), max);
        v.focus = max;
      }
      block.draw(renderMcpPanel(v, output.columns || 80, output.rows || 30));
    };

    const wasRaw = !!input.isRaw;
    input.setRawMode?.(true);
    input.resume();

    const close = () => {
      if (closed) return;
      closed = true;
      input.off('data', onData);
      block.erase();
      if (!wasRaw) input.setRawMode?.(false);
      resolve();
    };

    const push = (next: Screen) => {
      stack.push(next);
      draw();
    };
    const back = () => {
      if (stack.length <= 1) return close();
      stack.pop();
      draw();
    };
    const setFocus = (delta: number) => {
      const v = view();
      const count = rowCount(v);
      if (count === 0) return;
      const next = Math.max(0, Math.min(count - 1, v.focus + delta));
      if (next !== v.focus) {
        focusByScreen.set(screenKey(), next);
        draw();
      }
    };

    /** Runs a slow action with the panel showing "connecting…" and stays on the screen afterwards. */
    const run = async (action: () => Promise<unknown>) => {
      busy = true;
      draw();
      try {
        await action();
      } catch {}
      busy = false;
      draw();
    };

    const enter = () => {
      const v = view();
      const s = screen();
      if (s.kind === 'servers') {
        const server = orderedServers(v.statuses)[v.focus];
        if (server) push({ kind: 'server', name: server.name });
      } else if (s.kind === 'server') {
        const status = v.statuses.find((x) => x.name === s.name);
        if (!status) return;
        choose(actionsFor(status)[v.focus], status);
      } else if (s.kind === 'tools') {
        const tool = v.statuses.find((x) => x.name === s.name)?.toolInfo?.[v.focus];
        if (tool) push({ kind: 'tool', name: s.name, tool: tool.name });
      }
    };

    const choose = (action: ServerAction | undefined, status: MCPServerStatus) => {
      if (action === 'tools') push({ kind: 'tools', name: status.name });
      else if (action === 'reconnect') void run(() => controller.reconnect(status.name));
      else if (action === 'disable') void run(() => controller.setEnabled(status.name, false));
      else if (action === 'enable') void run(() => controller.setEnabled(status.name, true));
    };

    const handleKey = (key: string) => {
      if (key === '\x03') return close();
      if (busy) return;
      if (key === '\x1b[A' || key === '\x1bOA' || key === 'k') return setFocus(-1);
      if (key === '\x1b[B' || key === '\x1bOB' || key === 'j') return setFocus(1);
      if (key === '\x1b[H' || key === '\x1b[1~' || key === '\x1bOH' || key === 'g') return setFocus(-1e6);
      if (key === '\x1b[F' || key === '\x1b[4~' || key === '\x1bOF' || key === 'G') return setFocus(1e6);
      if (key === '\r' || key === '\n' || key === '\x1b[C' || key === '\x1bOC' || key === 'l') return enter();
      if (key === '\x1b' || key === '\x1b[D' || key === '\x1bOD' || key === 'h') return back();
      if (key === 'q') return close();
      if (key === 'r') {
        const s = screen();
        if (s.kind === 'servers') return void run(() => controller.reload());
        if (s.kind === 'server') return void run(() => controller.reconnect(s.name));
      }
      if (key >= '1' && key <= '9' && screen().kind === 'server') {
        const s = screen() as { kind: 'server'; name: string };
        const status = controller.getStatuses().find((x) => x.name === s.name);
        if (status) choose(actionsFor(status)[Number(key) - 1], status);
      }
    };

    const onData = (chunk: Buffer | string) => {
      for (const key of splitKeys(chunk.toString())) {
        if (closed) return;
        handleKey(normalizeKey(key));
      }
    };

    input.on('data', onData);
    draw();
  });
}
