// Presentation consumes only Durable public snapshots. Cursors remain opaque.
export const terminal = turn => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(turn.status);
export const activeTurn = snapshot => (snapshot?.turns || []).find(turn => !terminal(turn));
export const pendingInteractions = snapshot => {
    const signals = Object.values(snapshot?.projection?.signals || {});
    return Object.values(snapshot?.projection?.interactions || {})
        .filter(item => item.status === 'pending' && !item.automatic && !signals.some(signal =>
            signal.turnId === item.turnId && signal.key === item.key && signal.status !== 'withdrawn'));
};
export function items(snapshot) {
    const projection = snapshot?.projection || {};
    const messages = Object.values(projection.messages || {}).map(value => ({
        ...value, id: value.messageId, kind: 'message',
        role: value.message?.role || 'assistant',
        content: value.message?.content || [{ type: 'text', text: value.text }],
        status: value.complete ? 'completed' : 'generating'
    })).filter(value => value.content.length > 0);
    const tools = Object.entries(projection.tools || {}).map(([id, value]) => ({
        ...value, id, kind: 'tool', status: value.data.status
    }));
    return [...messages, ...tools].sort((a, b) => {
        const left = BigInt(a.sequence), right = BigInt(b.sequence);
        return left < right ? -1 : left > right ? 1 : 0;
    });
}
