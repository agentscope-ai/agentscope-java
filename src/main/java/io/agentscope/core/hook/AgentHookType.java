/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.hook;

/**
 * Enumeration of agent hook types corresponding to Python AgentHookTypes.
 *
 * These hooks allow interception of agent operations at key lifecycle points:
 * - PRE_REPLY/POST_REPLY: Before/after agent generates responses
 * - PRE_OBSERVE/POST_OBSERVE: Before/after agent processes observations
 * - PRE_PRINT/POST_PRINT: Before/after agent outputs messages
 */
public enum AgentHookType {
    /**
     * Hook executed before agent generates a reply.
     */
    PRE_REPLY,

    /**
     * Hook executed after agent generates a reply.
     */
    POST_REPLY,
}
