/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// ==================== i18n ====================

const i18n = {
    en: {
        subtitle: 'AI Fitness Coach with Interactive UI',
        welcomeTitle: 'Welcome!',
        welcomeDesc: "I'm your personal fitness coach. Tell me your goals, and I'll create a customized workout plan through interactive forms.",
        inputPlaceholder: 'Type a message...',
        you: 'You',
        assistant: 'Assistant',
        submit: 'Submit',
        yes: 'Yes',
        no: 'No',
        error: 'Error',
        selectPlaceholder: 'Select an option...',
        responded: 'Responded',
        approveAll: 'Approve All',
        rejectAll: 'Reject All',
        approved: 'Approved',
        rejected: 'Rejected',
        pendingApproval: 'Pending Approval',
        toolsPendingApproval: 'tool(s) pending approval',
        other: 'Other',
        otherPlaceholder: 'Please specify...',
        clearConfirm: 'Clear the current session?',
        clearFailed: 'Failed to clear session'
    },
    zh: {
        subtitle: 'AI 健身教练 · 交互式智能规划',
        welcomeTitle: '欢迎！',
        welcomeDesc: '我是你的私人健身教练。告诉我你的目标，我会通过交互式表单为你量身定制训练计划。',
        inputPlaceholder: '输入消息...',
        you: '你',
        assistant: '助手',
        submit: '提交',
        yes: '是',
        no: '否',
        error: '错误',
        selectPlaceholder: '请选择...',
        responded: '已回复',
        approveAll: '全部批准',
        rejectAll: '全部拒绝',
        approved: '已批准',
        rejected: '已拒绝',
        pendingApproval: '等待批准',
        toolsPendingApproval: '个工具等待批准',
        other: '其他',
        otherPlaceholder: '请输入...',
        clearConfirm: '确定清除当前会话？',
        clearFailed: '清除会话失败'
    }
};

// ==================== State ====================

const state = {
    sessionId: 'session_' + Date.now(),
    isProcessing: false,
    currentAssistantMessage: null,
    currentAssistantRawText: '',
    currentAbortController: null,
    pendingToolCalls: null,
    lang: navigator.language.startsWith('zh') ? 'zh' : 'en'
};

// ==================== DOM References ====================

const elements = {
    chatMessages: document.getElementById('chat-messages'),
    messageInput: document.getElementById('message-input'),
    sendBtn: document.getElementById('send-btn'),
    stopBtn: document.getElementById('stop-btn'),
    clearBtn: document.getElementById('clear-btn'),
    langEn: document.getElementById('lang-en'),
    langZh: document.getElementById('lang-zh'),
    subtitle: document.getElementById('subtitle'),
    welcomeTitle: document.getElementById('welcome-title'),
    welcomeDesc: document.getElementById('welcome-desc')
};

function t(key) {
    return i18n[state.lang][key] || key;
}

function updateI18n() {
    elements.subtitle.textContent = t('subtitle');
    elements.welcomeTitle.textContent = t('welcomeTitle');
    elements.welcomeDesc.textContent = t('welcomeDesc');
    elements.messageInput.placeholder = t('inputPlaceholder');
    elements.langEn.classList.toggle('active', state.lang === 'en');
    elements.langZh.classList.toggle('active', state.lang === 'zh');
}

function setLanguage(lang) {
    state.lang = lang;
    updateI18n();
}

// ==================== Initialization ====================

document.addEventListener('DOMContentLoaded', () => {
    setupEventListeners();
    updateI18n();
    autoResizeInput();
});

function setupEventListeners() {
    elements.sendBtn.addEventListener('click', sendMessage);
    elements.messageInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });
    elements.messageInput.addEventListener('input', autoResizeInput);
    elements.stopBtn.addEventListener('click', stopGeneration);
    elements.clearBtn.addEventListener('click', clearSession);
    elements.langEn.addEventListener('click', () => setLanguage('en'));
    elements.langZh.addEventListener('click', () => setLanguage('zh'));
}

function autoResizeInput() {
    const textarea = elements.messageInput;
    textarea.style.height = 'auto';
    textarea.style.height = Math.min(textarea.scrollHeight, 120) + 'px';
}

// ==================== Chat Logic ====================

/**
 * Send a POST request and stream SSE events from the response.
 */
async function sseRequest(url, body) {
    setProcessing(true);
    state.currentAssistantMessage = null;
    state.currentAbortController = new AbortController();

    try {
        const response = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
            signal: state.currentAbortController.signal
        });
        await processSSEStream(response);
    } catch (error) {
        if (error.name !== 'AbortError') {
            console.error('Request error:', error);
            addMessage('assistant', t('error') + ': ' + error.message);
        }
    } finally {
        setProcessing(false);
        state.currentAbortController = null;
    }
}

async function sendMessage() {
    const message = elements.messageInput.value.trim();
    if (!message || state.isProcessing) return;

    const welcome = document.querySelector('.welcome-message');
    if (welcome) welcome.remove();

    elements.messageInput.value = '';
    autoResizeInput();
    addMessage('user', message);

    await sseRequest('/api/chat', { sessionId: state.sessionId, message });
}

// Called from example buttons in HTML
function sendExample(btn) {
    elements.messageInput.value = btn.textContent;
    sendMessage();
}

async function stopGeneration() {
    // Abort the client-side SSE stream immediately so the UI stops
    if (state.currentAbortController) {
        state.currentAbortController.abort();
    }
    finalizeAssistantMessage();

    // Tell the server to interrupt the agent's reasoning loop
    try {
        await fetch(`/api/chat/interrupt/${encodeURIComponent(state.sessionId)}`, {
            method: 'POST'
        });
    } catch (error) {
        console.error('Interrupt failed:', error);
    }
}

async function clearSession() {
    if (!confirm(t('clearConfirm'))) return;
    try {
        await fetch(`/api/chat/session/${encodeURIComponent(state.sessionId)}`, {
            method: 'DELETE'
        });
        location.reload();
    } catch (error) {
        console.error(t('clearFailed'), error);
    }
}

// ==================== SSE Processing ====================

async function processSSEStream(response) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (value) buffer += decoder.decode(value, { stream: true });

            const lines = buffer.split('\n');
            buffer = lines.pop() || '';

            for (const line of lines) {
                if (line.startsWith('data:')) {
                    const data = line.slice(5).trim();
                    if (data) {
                        try {
                            handleChatEvent(JSON.parse(data));
                        } catch (e) {
                            console.error('Parse failed:', data, e);
                        }
                    }
                }
            }
            if (done) break;
        }
    } finally {
        reader.releaseLock();
    }
}

function handleChatEvent(event) {
    switch (event.type) {
        case 'TEXT':
            if (event.incremental) {
                appendToAssistantMessage(event.content);
            } else {
                // Non-incremental text = final event (content already streamed).
                // Just finalize the current message; don't create a duplicate.
                finalizeAssistantMessage();
            }
            break;
        case 'TOOL_USE':
            finalizeAssistantMessage();
            addToolUseEvent(event.toolId, event.toolName, event.toolInput);
            break;
        case 'TOOL_RESULT':
            addToolResultEvent(event.toolId, event.toolName, event.toolResult);
            break;
        case 'USER_INTERACTION':
            finalizeAssistantMessage();
            renderUserInteraction(event);
            break;
        case 'TOOL_CONFIRM':
            finalizeAssistantMessage();
            state.pendingToolCalls = event.pendingToolCalls;
            renderToolConfirmation(event.pendingToolCalls);
            break;
        case 'ERROR':
            finalizeAssistantMessage();
            addMessage('assistant', t('error') + ': ' + event.error);
            break;
        case 'COMPLETE':
            finalizeAssistantMessage();
            break;
    }
}

// ==================== Message Rendering ====================

function addMessage(role, content) {
    const div = document.createElement('div');
    div.className = `message ${role}`;

    const contentHtml = role === 'assistant'
        ? renderMarkdown(content)
        : escapeHtml(content);

    div.innerHTML = `
        <div class="message-avatar">${role === 'user' ? '👤' : '🤖'}</div>
        <div class="message-body">
            <div class="message-label">${role === 'user' ? t('you') : t('assistant')}</div>
            <div class="message-content${role === 'assistant' ? ' markdown-body' : ''}">${contentHtml}</div>
        </div>
    `;
    elements.chatMessages.appendChild(div);
    scrollToBottom();
    if (role === 'assistant') {
        state.currentAssistantMessage = div.querySelector('.message-content');
        state.currentAssistantRawText = content;
    }
}

function appendToAssistantMessage(content) {
    if (!state.currentAssistantMessage) {
        addMessage('assistant', content);
    } else {
        state.currentAssistantRawText += content;
        state.currentAssistantMessage.innerHTML = renderMarkdown(state.currentAssistantRawText);
        scrollToBottom();
    }
}

function finalizeAssistantMessage() {
    if (state.currentAssistantMessage && state.currentAssistantRawText) {
        state.currentAssistantMessage.innerHTML = renderMarkdown(state.currentAssistantRawText);
    }
    state.currentAssistantMessage = null;
    state.currentAssistantRawText = '';
}

function renderMarkdown(text) {
    if (!text) return '';
    if (typeof marked !== 'undefined') {
        return marked.parse(text);
    }
    return escapeHtml(text);
}

function addToolUseEvent(toolId, toolName, input) {
    const div = document.createElement('div');
    div.className = 'tool-event';
    div.id = `tool-${toolId}`;
    const inputJson = input ? JSON.stringify(input, null, 2) : '{}';
    div.innerHTML =
        '<div class="tool-event-header">' +
            '<span class="tool-icon">🔧</span>' +
            '<span class="tool-label">Tool: <code>' + escapeHtml(toolName) + '</code></span>' +
            '<span class="tool-status-badge executing">executing</span>' +
        '</div>' +
        '<div class="tool-event-params">' +
            '<pre class="tool-params-pre">' + escapeHtml(inputJson) + '</pre>' +
        '</div>';
    elements.chatMessages.appendChild(div);
    scrollToBottom();
}

function addToolResultEvent(toolId, toolName, result) {
    const toolDiv = document.getElementById(`tool-${toolId}`);
    if (toolDiv) {
        const badge = toolDiv.querySelector('.tool-status-badge');
        if (badge) {
            badge.className = 'tool-status-badge done';
            badge.textContent = 'done';
        }
    }
    const div = document.createElement('div');
    div.className = 'tool-event result';
    div.innerHTML =
        '<div class="tool-event-header">' +
            '<span class="tool-icon">✅</span>' +
            '<span class="tool-label">Result: <code>' + escapeHtml(toolName) + '</code></span>' +
        '</div>' +
        '<div class="tool-event-params">' +
            '<pre class="tool-params-pre">' + escapeHtml(result || '(empty)') + '</pre>' +
        '</div>';
    elements.chatMessages.appendChild(div);
    scrollToBottom();
}

// ==================== Tool Confirmation ====================

function renderToolConfirmation(pendingToolCalls) {
    const wrapper = document.createElement('div');
    wrapper.className = 'tool-confirm-group';

    for (const tool of pendingToolCalls) {
        const card = document.createElement('div');
        card.className = 'tool-confirm-card';
        card.id = `tool-${tool.id}`;
        const inputJson = tool.input ? JSON.stringify(tool.input, null, 2) : '{}';
        card.innerHTML =
            '<div class="tool-confirm-card-header">' +
                '<span class="tool-icon">' + (tool.needsConfirm ? '⚠️' : '🔧') + '</span>' +
                '<span class="tool-label">Tool: <code>' + escapeHtml(tool.name) + '</code></span>' +
                '<span class="tool-status-badge pending">' + t('pendingApproval') + '</span>' +
            '</div>' +
            '<div class="tool-confirm-card-params">' +
                '<pre class="tool-params-pre">' + escapeHtml(inputJson) + '</pre>' +
            '</div>';
        wrapper.appendChild(card);
    }

    const actionBar = document.createElement('div');
    actionBar.className = 'tool-confirm-batch-actions';
    actionBar.id = 'tool-confirm-batch';
    actionBar.innerHTML =
        '<span class="batch-label">' + pendingToolCalls.length + ' ' + t('toolsPendingApproval') + '</span>' +
        '<div class="batch-buttons">' +
            '<button class="btn btn-sm btn-confirm-approve" onclick="confirmToolCall(true)">' + t('approveAll') + '</button>' +
            '<button class="btn btn-sm btn-confirm-reject" onclick="confirmToolCall(false)">' + t('rejectAll') + '</button>' +
        '</div>';
    wrapper.appendChild(actionBar);

    elements.chatMessages.appendChild(wrapper);
    scrollToBottom();
}

async function confirmToolCall(confirmed) {
    if (!state.pendingToolCalls) return;

    const toolCalls = state.pendingToolCalls;

    for (const tool of toolCalls) {
        const badge = document.querySelector(`#tool-${tool.id} .tool-status-badge`);
        if (badge) {
            badge.className = confirmed ? 'tool-status-badge done' : 'tool-status-badge rejected';
            badge.textContent = confirmed ? t('approved') : t('rejected');
        }
    }
    const batchBar = document.getElementById('tool-confirm-batch');
    if (batchBar) {
        batchBar.innerHTML = confirmed
            ? `<span class="tool-status-badge done">✓ ${t('approved')}</span>`
            : `<span class="tool-status-badge rejected">✗ ${t('rejected')}</span>`;
    }

    const callInfos = toolCalls.map(tc => ({ id: tc.id, name: tc.name }));
    state.pendingToolCalls = null;

    await sseRequest('/api/chat/confirm', {
        sessionId: state.sessionId,
        confirmed,
        reason: confirmed ? null : 'Cancelled by user',
        toolCalls: callInfos
    });
}

// ========================================================
//  USER INTERACTION - Dynamic UI Component Registry
// ========================================================

/**
 * Component Registry: maps ui_type → render function.
 * Each render function receives the event data and returns a DOM element.
 */
const componentRegistry = {
    text:         renderTextInput,
    select:       (e) => renderSelectGroup(e, false),
    multi_select: (e) => renderSelectGroup(e, true),
    confirm:      (e) => renderSelectGroup(
                      { ...e, options: [{ value: 'yes', label: t('yes') }, { value: 'no', label: t('no') }] },
                      false),
    form:         renderForm,
    date:         renderDateInput,
    number:       renderNumberInput
};

/**
 * Main entry: render the appropriate UI based on the USER_INTERACTION event.
 */
function renderUserInteraction(event) {
    const container = document.createElement('div');
    container.className = 'message assistant';
    container.id = `interaction-${event.toolId}`;

    const body = document.createElement('div');
    body.className = 'message-body';

    // Avatar
    const avatar = document.createElement('div');
    avatar.className = 'message-avatar';
    avatar.textContent = '🤖';
    container.appendChild(avatar);

    // Label
    const label = document.createElement('div');
    label.className = 'message-label';
    label.textContent = t('assistant');
    body.appendChild(label);

    // Interaction card
    const card = document.createElement('div');
    card.className = 'interaction-card';

    // Question
    const questionDiv = document.createElement('div');
    questionDiv.className = 'interaction-question';
    questionDiv.textContent = event.question;
    card.appendChild(questionDiv);

    // Dynamic UI component
    const uiType = event.uiType || 'text';
    const renderer = componentRegistry[uiType] || renderTextInput;
    const componentDiv = renderer(event);
    componentDiv.className = (componentDiv.className || '') + ' interaction-component';
    card.appendChild(componentDiv);

    // Submit button
    const submitRow = document.createElement('div');
    submitRow.className = 'interaction-submit-row';
    const submitBtn = document.createElement('button');
    submitBtn.className = 'btn btn-primary interaction-submit-btn';
    submitBtn.textContent = t('submit');
    submitBtn.onclick = () => submitInteraction(event.toolId, uiType, card);
    submitRow.appendChild(submitBtn);
    card.appendChild(submitRow);

    body.appendChild(card);
    container.appendChild(body);
    elements.chatMessages.appendChild(container);
    scrollToBottom();
}

// ==================== UI Components ====================

function renderTextInput(event) {
    const div = document.createElement('div');
    const input = document.createElement('textarea');
    input.className = 'interaction-text-input';
    input.name = '_response';
    input.placeholder = event.defaultValue || '';
    input.rows = 2;
    div.appendChild(input);
    return div;
}

function renderSelectGroup(event, multi = false) {
    const div = document.createElement('div');
    div.className = 'interaction-select-group' + (multi ? ' multi' : '');

    const deselectAll = () => {
        div.querySelectorAll('.select-option-btn').forEach(b => b.classList.remove('selected'));
        const otherInput = div.querySelector('.select-other-input');
        if (otherInput) otherInput.classList.add('hidden');
    };

    for (const option of (event.options || [])) {
        const btn = document.createElement('button');
        btn.className = 'select-option-btn';
        btn.dataset.value = option.value || option.label || option;
        btn.textContent = option.label || option.value || option;
        btn.onclick = () => {
            if (!multi) deselectAll();
            btn.classList.toggle('selected');
        };
        div.appendChild(btn);
    }

    if (event.allowOther) {
        const otherBtn = document.createElement('button');
        otherBtn.className = 'select-option-btn select-other-btn';
        otherBtn.dataset.value = '__other__';
        otherBtn.textContent = '✏️ ' + t('other');

        const otherInput = document.createElement('input');
        otherInput.type = 'text';
        otherInput.className = 'select-other-input hidden';
        otherInput.name = '_other';
        otherInput.placeholder = t('otherPlaceholder');

        otherBtn.onclick = () => {
            if (!multi) deselectAll();
            otherBtn.classList.toggle('selected');
            otherInput.classList.toggle('hidden', !otherBtn.classList.contains('selected'));
            if (otherBtn.classList.contains('selected')) otherInput.focus();
        };

        div.appendChild(otherBtn);
        div.appendChild(otherInput);
    }

    return div;
}

function renderForm(event) {
    const div = document.createElement('div');
    div.className = 'interaction-form';

    const fields = event.fields || [];
    for (const field of fields) {
        const group = document.createElement('div');
        group.className = 'form-field';

        const label = document.createElement('label');
        label.className = 'form-field-label';
        label.textContent = (field.label || field.name) + (field.required ? ' *' : '');
        group.appendChild(label);

        if (field.type === 'select' && field.options) {
            const select = document.createElement('select');
            select.className = 'form-field-input';
            select.name = field.name;
            if (field.required) select.required = true;

            const defaultOpt = document.createElement('option');
            defaultOpt.value = '';
            defaultOpt.textContent = t('selectPlaceholder');
            select.appendChild(defaultOpt);

            for (const opt of field.options) {
                const option = document.createElement('option');
                option.value = opt.value || opt.label || opt;
                option.textContent = opt.label || opt.value || opt;
                select.appendChild(option);
            }
            group.appendChild(select);
        } else if (field.type === 'textarea') {
            const textarea = document.createElement('textarea');
            textarea.className = 'form-field-input';
            textarea.name = field.name;
            textarea.placeholder = field.placeholder || '';
            textarea.rows = 3;
            if (field.required) textarea.required = true;
            group.appendChild(textarea);
        } else {
            const input = document.createElement('input');
            input.className = 'form-field-input';
            input.type = field.type || 'text';
            input.name = field.name;
            input.placeholder = field.placeholder || '';
            if (field.required) input.required = true;
            if (field.type === 'number') {
                if (field.min !== undefined) input.min = field.min;
                if (field.max !== undefined) input.max = field.max;
                if (field.step !== undefined) input.step = field.step;
            }
            if (field.type === 'date') {
                if (field.min) input.min = field.min;
                if (field.max) input.max = field.max;
            }
            group.appendChild(input);
        }

        div.appendChild(group);
    }
    return div;
}

function renderDateInput(event) {
    const div = document.createElement('div');
    const input = document.createElement('input');
    input.type = 'date';
    input.className = 'form-field-input';
    input.name = '_response';
    if (event.defaultValue) input.value = event.defaultValue;
    div.appendChild(input);
    return div;
}

function renderNumberInput(event) {
    const div = document.createElement('div');
    const input = document.createElement('input');
    input.type = 'number';
    input.className = 'form-field-input';
    input.name = '_response';
    if (event.defaultValue) input.value = event.defaultValue;
    div.appendChild(input);
    return div;
}

// ==================== Submit Interaction ====================

/**
 * Collect the user's response from the UI component and send it back to the server.
 */
async function submitInteraction(toolId, uiType, card) {
    let response = collectResponse(uiType, card);
    if (response === null || response === undefined) return;
    if (typeof response === 'string' && response.trim() === '') return;

    // Disable the interaction card
    card.classList.add('responded');
    card.querySelectorAll('button, input, select, textarea').forEach(el => el.disabled = true);

    // Replace submit button with responded badge
    const submitRow = card.querySelector('.interaction-submit-row');
    if (submitRow) {
        submitRow.innerHTML = `<span class="responded-badge">✓ ${t('responded')}</span>`;
    }

    await sseRequest('/api/chat/respond', {
        sessionId: state.sessionId,
        toolId,
        response
    });
}

/**
 * Extract the user's response from the rendered UI component.
 */
function collectResponse(uiType, card) {
    switch (uiType) {
        case 'select':
        case 'confirm': {
            const selected = card.querySelector('.select-option-btn.selected');
            if (!selected) return null;
            if (selected.dataset.value === '__other__') {
                const otherInput = card.querySelector('.select-other-input');
                return otherInput && otherInput.value.trim() ? otherInput.value.trim() : null;
            }
            return selected.dataset.value;
        }
        case 'multi_select': {
            const selected = card.querySelectorAll('.select-option-btn.selected');
            if (selected.length === 0) return null;
            const values = Array.from(selected)
                .map(b => b.dataset.value)
                .filter(v => v !== '__other__');
            const otherInput = card.querySelector('.select-other-input');
            if (otherInput && otherInput.value.trim()) {
                values.push(otherInput.value.trim());
            }
            return values.length > 0 ? values : null;
        }
        case 'form': {
            const result = {};
            let valid = true;
            card.querySelectorAll('.form-field-input').forEach(el => {
                if (el.name) result[el.name] = el.value;
                if (el.required && !el.value.trim()) {
                    el.classList.add('invalid');
                    valid = false;
                } else {
                    el.classList.remove('invalid');
                }
            });
            if (!valid) return null;
            return result;
        }
        case 'text':
        case 'date':
        case 'number':
        default: {
            const input = card.querySelector('input, textarea');
            return input ? input.value : null;
        }
    }
}

// ==================== Utilities ====================

function setProcessing(processing) {
    state.isProcessing = processing;
    elements.sendBtn.disabled = processing;
    elements.messageInput.disabled = processing;
    elements.stopBtn.classList.toggle('hidden', !processing);
    elements.sendBtn.classList.toggle('hidden', processing);
}

function scrollToBottom() {
    elements.chatMessages.scrollTop = elements.chatMessages.scrollHeight;
}

function escapeHtml(text) {
    if (text === null || text === undefined) return '';
    const div = document.createElement('div');
    div.textContent = String(text);
    return div.innerHTML;
}
