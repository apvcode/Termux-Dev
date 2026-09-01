import pc from 'picocolors';
export function getModeIndicator(planMode, isYolo = false) {
    if (isYolo) {
        return {
            mode: 'YOLO',
            icon: pc.red('◆'),
            badge: pc.bgRed(pc.bold(pc.white(' YOLO '))),
            accentFn: pc.red
        };
    }
    if (planMode) {
        return {
            mode: 'PLAN',
            icon: pc.cyan('◆'),
            badge: pc.bgCyan(pc.bold(pc.black(' PLAN '))),
            accentFn: pc.cyan
        };
    }
    return {
        mode: 'AGENT',
        icon: pc.green('◆'),
        badge: pc.bgGreen(pc.bold(pc.black(' AGENT '))),
        accentFn: pc.green
    };
}
export function formatPromptHeader(planMode, isYolo = false, customMessage) {
    const indicator = getModeIndicator(planMode, isYolo);
    const targetMode = planMode ? 'AGENT' : 'PLAN';
    const msg = customMessage || 'Ask anything...';
    return `${indicator.icon}  ${pc.bold(msg)} ${pc.dim(`(Tab = ${targetMode})`)}`;
}
