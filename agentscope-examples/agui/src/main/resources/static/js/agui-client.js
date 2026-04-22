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

/**
 * AG-UI Protocol Client
 *
 * A minimal JavaScript client for communicating with AG-UI protocol servers.
 *
 * @example
 * const client = new AguiClient('/agui/run');
 * await client.run({
 *     threadId: 'thread-123',
 *     runId: 'run-456',
 *     messages: [{ id: 'msg-1', role: 'user', content: 'Hello!' }]
 * }, {
 *     onTextContent: (delta) => console.log(delta),
 *     onReasoningContent: (delta) => console.log('Reasoning:', delta),
 *     onRunFinished: () => console.log('Done')
 * });
 */
class AguiClient {
    /**
     * Create a new AG-UI client.
     * @param {string} endpoint - The AG-UI run endpoint URL
     */
    constructor(endpoint) {
        this.endpoint = endpoint;
        this.abortController = null;
    }

    /**
     * Abort the current run if one is in progress.
     * This will close the SSE connection and trigger agent interruption on the backend.
     */
    abort() {
        if (this.abortController) {
            console.log('Aborting current run...');
            this.abortController.abort();
            this.abortController = null;
        }
    }

    /**
     * Check if a run is currently in progress.
     * @returns {boolean} True if running
     */
    isRunning() {
        return this.abortController !== null;
    }

    /**
     * Run an agent with the given input.
     * @param {Object} input - The run input
     * @param {string} input.threadId - Thread identifier
     * @param {string} input.runId - Run identifier
     * @param {Array} input.messages - Array of messages
     * @param {Array} [input.tools] - Optional tools
     * @param {Array} [input.context] - Optional context
     * @param {Object} [input.state] - Optional state
     * @param {Object} [input.forwardedProps] - Optional forwarded properties
     * @param {Object} callbacks - Event callbacks
     * @param {Function} [callbacks.onReasoningMessageStart] - Called when reasoning message starts
     * @param {Function} [callbacks.onReasoningContent] - Called with reasoning content delta
     * @param {Function} [callbacks.onReasoningMessageEnd] - Called when reasoning message ends
     * @returns {Promise} Resolves when the run completes
     */
    async run(input, callbacks = {}) {
        // Create abort controller for this run
        this.abortController = new AbortController();
        const signal = this.abortController.signal;

        const response = await fetch(this.endpoint, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream'
            },
            body: JSON.stringify(input),
            signal: signal
        });

        if (!response.ok) {
            this.abortController = null;
            throw new Error(`HTTP error: ${response.status} ${response.statusText}`);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        console.log('Starting to read SSE stream...');
        let eventSequence = 0;

        try {
            while (true) {
                const { done, value } = await reader.read();

                if (done) {
                    console.log('Stream ended, remaining buffer:', buffer);
                    break;
                }

                const chunk = decoder.decode(value, { stream: true });
                console.log('Received chunk:', chunk.length, 'bytes');
                buffer += chunk;

                // Try both \n\n and \r\n\r\n as delimiters
                let delimiter = '\n\n';
                let delimiterIndex = buffer.indexOf(delimiter);

                // If \n\n not found, try \r\n\r\n (Windows-style)
                if (delimiterIndex === -1) {
                    delimiter = '\r\n\r\n';
                    delimiterIndex = buffer.indexOf(delimiter);
                }

                // Process complete SSE messages
                while (delimiterIndex !== -1) {
                    const message = buffer.substring(0, delimiterIndex);
                    buffer = buffer.substring(delimiterIndex + delimiter.length);

                    console.log('Processing SSE message:', message.substring(0, 100));

                    // Process each line in the message
                    const lines = message.split(/\r?\n/);
                    for (const line of lines) {
                        if (line.startsWith('data:')) {
                            try {
                                // Handle both "data: " and "data:" formats
                                const jsonStr = line.startsWith('data: ') ? line.substring(6) : line.substring(5);
                                const event = JSON.parse(jsonStr);
                                eventSequence++;
                                console.log(`[${eventSequence}] Received event:`, event.type, event);
                                this.handleEvent(event, callbacks);
                            } catch (e) {
                                console.warn('Failed to parse event:', line, e);
                            }
                        }
                    }

                    // Check for next delimiter
                    delimiterIndex = buffer.indexOf('\n\n');
                    if (delimiterIndex === -1) {
                        delimiterIndex = buffer.indexOf('\r\n\r\n');
                        if (delimiterIndex !== -1) delimiter = '\r\n\r\n';
                    } else {
                        delimiter = '\n\n';
                    }
                }
            }

            // Process any remaining data in buffer
            if (buffer.trim()) {
                console.log('Processing remaining buffer:', buffer);
                const lines = buffer.split(/\r?\n/);
                for (const line of lines) {
                    if (line.startsWith('data:')) {
                        try {
                            const jsonStr = line.startsWith('data: ') ? line.substring(6) : line.substring(5);
                            const event = JSON.parse(jsonStr);
                            console.log('Received final event:', event.type, event);
                            this.handleEvent(event, callbacks);
                        } catch (e) {
                            console.warn('Failed to parse remaining event:', line, e);
                        }
                    }
                }
            }
        } finally {
            reader.releaseLock();
            this.abortController = null;
        }
    }

    /**
     * Handle an AG-UI event.
     * @param {Object} event - The event object
     * @param {Object} callbacks - Event callbacks
     */
    handleEvent(event, callbacks) {
        if (!event || !event.type) {
            console.warn('Invalid event received:', event);
            return;
        }

        const type = event.type;

        try {
            switch (type) {
                case 'RUN_STARTED':
                    callbacks.onRunStarted?.(event.threadId, event.runId);
                    break;

                case 'RUN_FINISHED':
                    callbacks.onRunFinished?.(event.threadId, event.runId);
                    break;

                case 'TEXT_MESSAGE_START':
                    callbacks.onTextMessageStart?.(event.messageId, event.role);
                    break;

                case 'TEXT_MESSAGE_CONTENT':
                    // Ensure delta is not null/undefined
                    const delta = event.delta || '';
                    if (delta) {
                        callbacks.onTextContent?.(delta, event.messageId);
                    }
                    break;

                case 'TEXT_MESSAGE_END':
                    callbacks.onTextMessageEnd?.(event.messageId);
                    break;

                case 'REASONING_MESSAGE_START':
                    callbacks.onReasoningMessageStart?.(event.messageId, event.role);
                    break;

                case 'REASONING_MESSAGE_CONTENT':
                    // Ensure delta is not null/undefined
                    const reasoningDelta = event.delta || '';
                    if (reasoningDelta) {
                        callbacks.onReasoningContent?.(reasoningDelta, event.messageId);
                    }
                    break;

                case 'REASONING_MESSAGE_END':
                    callbacks.onReasoningMessageEnd?.(event.messageId);
                    break;

                case 'TOOL_CALL_START':
                    callbacks.onToolCallStart?.(event.toolCallId, event.toolCallName);
                    break;

                case 'TOOL_CALL_ARGS':
                    callbacks.onToolCallArgs?.(event.toolCallId, event.delta);
                    break;

                case 'TOOL_CALL_END':
                    callbacks.onToolCallEnd?.(event.toolCallId);
                    break;

                case 'STATE_SNAPSHOT':
                    callbacks.onStateSnapshot?.(event.snapshot);
                    break;

                case 'STATE_DELTA':
                    callbacks.onStateDelta?.(event.delta);
                    break;

                case 'RAW':
                    if (event.rawEvent?.error) {
                        callbacks.onError?.(event.rawEvent.error);
                    } else {
                        callbacks.onRawEvent?.(event.rawEvent);
                    }
                    break;

                default:
                    console.log('Unknown event type:', type, event);
            }
        } catch (error) {
            console.error('Error handling event:', type, error);
        }
    }
}

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { AguiClient };
}

