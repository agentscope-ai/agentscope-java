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
 * Frontend Tools Manager
 * 
 * Manages frontend tool definitions and execution for the AG-UI protocol.
 * This module allows defining tools that execute in the browser and return
 * results to the AI agent.
 */

/**
 * Preset tool definitions
 */
const PRESET_TOOLS = {
    'browser-info': {
        name: 'get_browser_info',
        description: 'Get information about the current browser including user agent, screen size, language, and current URL',
        parameters: {
            type: 'object',
            properties: {},
            required: []
        }
    },
    'local-storage': {
        name: 'search_local_storage',
        description: 'Search browser local storage for keys matching a pattern',
        parameters: {
            type: 'object',
            properties: {
                key_pattern: {
                    type: 'string',
                    description: 'Pattern to search for in local storage keys (case-insensitive substring match)'
                }
            },
            required: ['key_pattern']
        }
    },
    'clipboard': {
        name: 'read_clipboard',
        description: 'Read text content from the system clipboard (requires user permission)',
        parameters: {
            type: 'object',
            properties: {},
            required: []
        }
    },
    'current-time': {
        name: 'get_current_time',
        description: 'Get the current date and time from the client device',
        parameters: {
            type: 'object',
            properties: {
                format: {
                    type: 'string',
                    description: 'Time format: "iso", "locale", or "timestamp"',
                    enum: ['iso', 'locale', 'timestamp']
                }
            }
        }
    },
    'page-info': {
        name: 'get_page_info',
        description: 'Get information about the current webpage including title, URL, and meta description',
        parameters: {
            type: 'object',
            properties: {},
            required: []
        }
    }
};

/**
 * Tool execution handlers
 */
const TOOL_HANDLERS = {
    get_browser_info: async (args) => {
        return {
            user_agent: navigator.userAgent,
            platform: navigator.platform,
            language: navigator.language,
            languages: navigator.languages,
            screen: {
                width: screen.width,
                height: screen.height,
                availWidth: screen.availWidth,
                availHeight: screen.availHeight,
                colorDepth: screen.colorDepth
            },
            window: {
                innerWidth: window.innerWidth,
                innerHeight: window.innerHeight
            },
            url: window.location.href,
            hostname: window.location.hostname,
            online: navigator.onLine,
            cookie_enabled: navigator.cookieEnabled,
            touch_support: 'ontouchstart' in window || navigator.maxTouchPoints > 0
        };
    },

    search_local_storage: async (args) => {
        const pattern = args.key_pattern?.toLowerCase() || '';
        const results = {};
        
        for (let i = 0; i < localStorage.length; i++) {
            const key = localStorage.key(i);
            if (!pattern || key.toLowerCase().includes(pattern)) {
                try {
                    const value = localStorage.getItem(key);
                    // Try to parse as JSON, otherwise keep as string
                    try {
                        results[key] = JSON.parse(value);
                    } catch {
                        results[key] = value;
                    }
                } catch (e) {
                    results[key] = `[Error reading: ${e.message}]`;
                }
            }
        }
        
        return {
            pattern: pattern,
            match_count: Object.keys(results).length,
            matches: results
        };
    },

    read_clipboard: async (args) => {
        try {
            // Check if clipboard API is available
            if (!navigator.clipboard) {
                throw new Error('Clipboard API not available. Make sure you are on HTTPS.');
            }
            
            const text = await navigator.clipboard.readText();
            return {
                success: true,
                content: text,
                length: text.length
            };
        } catch (err) {
            return {
                success: false,
                error: err.message,
                hint: 'User may need to grant clipboard permission or interact with the page first'
            };
        }
    },

    get_current_time: async (args) => {
        const now = new Date();
        const format = args.format || 'iso';
        
        let time;
        switch (format) {
            case 'timestamp':
                time = now.getTime();
                break;
            case 'locale':
                time = now.toLocaleString();
                break;
            case 'iso':
            default:
                time = now.toISOString();
        }
        
        return {
            time: time,
            timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
            timezone_offset: now.getTimezoneOffset(),
            format: format
        };
    },

    get_page_info: async (args) => {
        const metaDescription = document.querySelector('meta[name="description"]')?.content || '';
        const metaKeywords = document.querySelector('meta[name="keywords"]')?.content || '';
        
        return {
            title: document.title,
            url: window.location.href,
            description: metaDescription,
            keywords: metaKeywords,
            referrer: document.referrer || null,
            charset: document.characterSet,
            last_modified: document.lastModified
        };
    }
};

/**
 * Frontend Tools Manager Class
 */
class FrontendToolsManager {
    constructor() {
        this.tools = [];
        this.pendingCalls = new Map();
        this.pendingResults = [];
    }

    /**
     * Get all defined tools
     * @returns {Array} Array of tool definitions
     */
    getTools() {
        return this.tools;
    }

    /**
     * Add a custom tool
     * @param {Object} tool - Tool definition
     * @param {string} tool.name - Tool name
     * @param {string} tool.description - Tool description
     * @param {Object} tool.parameters - JSON Schema parameters
     * @returns {boolean} True if added successfully
     */
    addTool(tool) {
        // Validate tool
        if (!tool.name || !tool.description) {
            console.error('Tool must have name and description');
            return false;
        }

        // Check for duplicates
        if (this.tools.some(t => t.name === tool.name)) {
            console.warn(`Tool ${tool.name} already exists, skipping`);
            return false;
        }

        // Ensure parameters has correct structure
        const toolDef = {
            name: tool.name,
            description: tool.description,
            parameters: tool.parameters || { type: 'object', properties: {} }
        };

        this.tools.push(toolDef);
        console.log(`Added frontend tool: ${tool.name}`);
        return true;
    }

    /**
     * Add a preset tool
     * @param {string} presetName - Name of the preset
     * @returns {boolean} True if added successfully
     */
    addPresetTool(presetName) {
        const preset = PRESET_TOOLS[presetName];
        if (!preset) {
            console.error(`Unknown preset: ${presetName}`);
            return false;
        }

        return this.addTool(preset);
    }

    /**
     * Remove a tool by index
     * @param {number} index - Tool index
     */
    removeTool(index) {
        if (index >= 0 && index < this.tools.length) {
            const removed = this.tools.splice(index, 1);
            console.log(`Removed frontend tool: ${removed[0].name}`);
        }
    }

    /**
     * Check if a tool exists
     * @param {string} toolName - Tool name
     * @returns {boolean}
     */
    hasTool(toolName) {
        return this.tools.some(t => t.name === toolName);
    }

    /**
     * Execute a tool
     * @param {string} toolName - Name of the tool to execute
     * @param {Object} args - Tool arguments
     * @returns {Promise<Object>} Tool execution result
     */
    async executeTool(toolName, args = {}) {
        const handler = TOOL_HANDLERS[toolName];
        if (!handler) {
            throw new Error(`No handler found for tool: ${toolName}`);
        }

        console.log(`Executing frontend tool: ${toolName}`, args);
        
        try {
            const result = await handler(args);
            return {
                success: true,
                output: result
            };
        } catch (error) {
            console.error(`Error executing tool ${toolName}:`, error);
            return {
                success: false,
                error: error.message
            };
        }
    }

    /**
     * Add a pending tool call
     * @param {string} toolCallId - Tool call ID
     * @param {string} toolName - Tool name
     */
    addPendingCall(toolCallId, toolName) {
        this.pendingCalls.set(toolCallId, { toolCallId, toolName, args: '' });
    }

    /**
     * Append args to a pending tool call
     * @param {string} toolCallId - Tool call ID
     * @param {string} delta - Args delta
     */
    appendToolCallArgs(toolCallId, delta) {
        const call = this.pendingCalls.get(toolCallId);
        if (call) {
            call.args += delta;
        }
    }

    /**
     * Get a pending tool call
     * @param {string} toolCallId - Tool call ID
     * @returns {Object|null}
     */
    getPendingCall(toolCallId) {
        const call = this.pendingCalls.get(toolCallId);
        if (!call) return null;

        // Parse args if available
        let parsedArgs = {};
        if (call.args) {
            try {
                parsedArgs = JSON.parse(call.args);
            } catch (e) {
                console.warn(`Failed to parse tool call args for ${toolCallId}:`, e);
            }
        }

        return { ...call, parsedArgs };
    }

    /**
     * Add a pending result to be sent in next request
     * @param {string} toolCallId - Tool call ID
     * @param {Object} result - Tool execution result
     */
    addPendingResult(toolCallId, result) {
        this.pendingResults.push({
            toolCallId,
            ...result
        });
    }

    /**
     * Get all pending results
     * @returns {Array}
     */
    getPendingResults() {
        return this.pendingResults;
    }

    /**
     * Clear pending results
     */
    clearPendingResults() {
        this.pendingResults = [];
        this.pendingCalls.clear();
    }
}

// Export for use in other scripts
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { FrontendToolsManager, PRESET_TOOLS, TOOL_HANDLERS };
}