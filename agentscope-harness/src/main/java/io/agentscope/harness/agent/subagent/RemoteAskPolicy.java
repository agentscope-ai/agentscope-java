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
package io.agentscope.harness.agent.subagent;

/**
 * Policy for resolving a remote subagent's tool-confirmation (HITL) requests when the parent
 * cannot forward the decision to an interactive human within this call.
 */
public enum RemoteAskPolicy {

    /**
     * Auto-deny every pending remote confirmation (safe default). Applied whenever the parent
     * call has no live event-stream consumer, or when this policy is explicitly configured.
     */
    DENY,

    /**
     * Forward the confirmation request upstream (via the parent's {@code AgentEventEmitter}
     * stream) instead of auto-denying, so an interactive consumer can decide.
     */
    PROPAGATE
}
