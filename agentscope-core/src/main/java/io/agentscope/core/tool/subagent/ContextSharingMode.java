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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mode for sharing memory context between parent agent and sub-agent.
 *
 * <p>This enum defines how memory is shared between the parent agent and sub-agent:
 *
 * <ul>
 *   <li><b>shared (default):</b> SubAgent receives a forked copy of parent's memory at invocation
 *       time, with pending tool calls removed. The sub-agent can see the conversation context but
 *       changes don't affect parent's memory. SubAgent uses parent's system prompt. This provides
 *       context visibility while avoiding validation issues with pending tool calls.
 *   <li><b>fork:</b> SubAgent gets a copy (fork) of parent's memory at invocation time, with
 *       pending tool calls removed. SubAgent's execution doesn't affect parent's memory. SubAgent
 *       uses parent's system prompt.
 *   <li><b>new:</b> SubAgent has completely independent memory with its own system prompt. No
 *       context from parent.
 * </ul>
 *
 * <p><b>Implementation Note:</b> Both SHARED and FORK modes use forked memory copies because the
 * parent's memory contains the pending tool_use block that invoked the sub-agent. Directly sharing
 * this memory would cause validation errors when the sub-agent tries to add new messages. The
 * pending tool calls are removed from the forked copy to ensure proper message sequence.
 *
 * <p>Aligned with skill specification: https://code.claude.com/docs/zh-CN/skills
 */
public enum ContextSharingMode {
    /**
     * Shared memory mode (default).
     *
     * <p>SubAgent receives a forked copy of parent's memory at invocation time, with pending tool
     * calls removed. This provides the sub-agent with full conversation context visibility while
     * ensuring isolation - changes made by the sub-agent don't affect the parent's memory.
     *
     * <p><b>Note:</b> Despite the name "shared", this mode uses a forked copy for technical
     * reasons. The parent's memory cannot be directly shared because it contains the pending
     * tool_use block that invoked this sub-agent, which would cause validation errors.
     *
     * <p>This is the default mode when context is not specified in skill.md.
     */
    SHARED,

    /**
     * Fork memory mode.
     *
     * <p>SubAgent gets a copy (fork) of parent's memory at invocation time, with pending tool calls
     * removed. Changes made by SubAgent don't affect parent's memory. SubAgent uses parent's system
     * prompt context.
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
    NEW;

    private static final Logger logger = LoggerFactory.getLogger(ContextSharingMode.class);

    /**
     * Parses the context sharing mode from a string value.
     *
     * <p>Supported values:
     *
     * <ul>
     *   <li>null, empty, "shared" - SHARED (default)
     *   <li>"fork" - FORK
     *   <li>"new" - NEW
     * </ul>
     *
     * @param context The context string to parse
     * @return The corresponding ContextSharingMode, defaults to SHARED for unknown values
     */
    public static ContextSharingMode fromString(String context) {
        if (context == null || context.isEmpty() || "shared".equalsIgnoreCase(context)) {
            return SHARED;
        } else if ("fork".equalsIgnoreCase(context)) {
            return FORK;
        } else if ("new".equalsIgnoreCase(context)) {
            return NEW;
        } else {
            logger.warn(
                    "Unknown context mode '{}', defaulting to SHARED. "
                            + "Supported values: shared, fork, new",
                    context);
            return SHARED;
        }
    }
}
