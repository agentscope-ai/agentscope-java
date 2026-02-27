/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tool.subagent;

/**
 * Mode for sharing memory context between parent agent and sub-agent.
 *
 * <p>This enum defines how memory is shared between the parent agent and sub-agent:
 *
 * <ul>
 *   <li><b>shared (default):</b> SubAgent shares the same memory instance with parent. All messages
 *       are immediately visible to both agents. SubAgent uses parent's system prompt.
 *   <li><b>fork:</b> SubAgent gets a copy (fork) of parent's memory at invocation time. SubAgent's
 *       execution doesn't affect parent's memory. SubAgent uses parent's system prompt.
 *   <li><b>new:</b> SubAgent has completely independent memory with its own system prompt. No
 *       context from parent.
 * </ul>
 *
 * <p>Aligned with skill specification: https://code.claude.com/docs/zh-CN/skills
 */
public enum ContextSharingMode {
    /**
     * Shared memory mode (default).
     *
     * <p>SubAgent uses the same memory instance as the parent agent. All messages are immediately
     * visible to both. SubAgent uses parent's system prompt context.
     *
     * <p>This is the default mode when context is not specified in skill.md.
     */
    SHARED,

    /**
     * Fork memory mode.
     *
     * <p>SubAgent gets a copy (fork) of parent's memory at invocation time. Changes made by
     * SubAgent don't affect parent's memory. SubAgent uses parent's system prompt context.
     *
     * <p>Use this when you need context awareness but want isolation from parent's memory.
     */
    FORK,

    /**
     * New independent memory mode.
     *
     * <p>SubAgent has completely independent memory with its own system prompt. No context from
     * parent is available.
     *
     * <p>Use this for isolated tasks that don't need parent context.
     */
    NEW
}
