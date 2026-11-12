import test from 'node:test';
import assert from 'node:assert/strict';
import { activeTurn, items, pendingInteractions } from '../../main/resources/static/chat-view.mjs';

test('controls target the unfinished head instead of the last queued Turn', () => {
    for (const status of ['WAITING', 'PAUSING', 'PAUSED', 'RUNNING']) {
        const head = { turnId: 'head', status };
        assert.equal(activeTurn({ turns: [{ status: 'SUCCEEDED' }, head, { turnId: 'later', status: 'QUEUED' }] }), head);
    }
    assert.equal(activeTurn({ turns: [{ status: 'CANCELLED' }] }), undefined);
});

test('drafts, complete messages and tool cards use public fields and exact sequence ordering', () => {
    const projection = {
        messages: {
            draft: { messageId: 'draft', sequence: '9007199254740993', text: 'partial', complete: false },
            final: { messageId: 'final', sequence: '9007199254740995', text: 'final', complete: true,
                message: { role: 'assistant', content: [{ type: 'text', text: 'final' }] } }
        },
        tools: { tool: { sequence: '9007199254740994', data: { status: 'SUCCESS',
            arguments: { topic: 'A' }, content: [{ type: 'text', text: 'result' }],
            progress: [{ type: 'text', text: 'progress' }] } } }
    };
    const result = items({ projection });
    assert.deepEqual(result.map(item => item.id), ['draft', 'tool', 'final']);
    assert.equal(result[0].status, 'generating');
    assert.equal(result[0].content[0].text, 'partial');
    assert.equal(result[1].data.progress[0].text, 'progress');
    assert.equal(result[2].content[0].text, 'final');
    assert.deepEqual(items({ projection: structuredClone(projection) }), result);
});

test('only unanswered manual interactions render an answer form', () => {
    const manual = { id: 'manual', status: 'pending', automatic: false };
    assert.deepEqual(pendingInteractions({ projection: { interactions: {
        manual, resolved: { status: 'resolved' }, automatic: { status: 'pending', automatic: true }
    } } }), [manual]);
});

test('a recorded answer hides its form while the Turn remains paused; withdrawn answers can be replaced', () => {
    const interaction = { id: 'question', turnId: 'turn', key: 'interaction:q', status: 'pending', automatic: false };
    const signal = { turnId: 'turn', key: 'interaction:q', status: 'accepted' };
    const snapshot = { turns: [{ turnId: 'turn', status: 'PAUSED' }],
        projection: { interactions: { question: interaction }, signals: { answer: signal } } };
    assert.deepEqual(pendingInteractions(snapshot), []);
    assert.equal(activeTurn(snapshot).status, 'PAUSED');
    signal.status = 'withdrawn';
    assert.deepEqual(pendingInteractions(snapshot), [interaction]);
    signal.status = 'accepted'; signal.turnId = 'another-turn';
    assert.deepEqual(pendingInteractions(snapshot), [interaction]);
});
