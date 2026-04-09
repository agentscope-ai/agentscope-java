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
package io.agentscope.core.hook;

/**
 * Enum representing all hook event types.
 *
 * <p>These events are fired at different stages of agent execution and can be
 * intercepted by implementing the {@link Hook} interface.
 *
 * @see Hook
 * @see HookEvent
 */
public enum HookEventType {
    /** Before agent starts processing */
    PRE_CALL,

    /** After agent completes processing */
    POST_CALL,

    /** Before LLM reasoning */
    PRE_REASONING,

    /** After LLM reasoning completes */
    POST_REASONING,

    /** During LLM reasoning streaming */
    REASONING_CHUNK,

    /** Before tool execution */
    PRE_ACTING,

    /** After tool execution completes */
    POST_ACTING,

    /** During tool execution streaming */
    ACTING_CHUNK,

    /** Before summary generation (when max iterations reached) */
    PRE_SUMMARY,

    /** After summary generation completes */
    POST_SUMMARY,

    /** During summary streaming */
    SUMMARY_CHUNK,

    /** When an error occurs */
    ERROR
}
