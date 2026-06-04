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
package io.agentscope.extensions.agentprotocol;

/** Workspace partitioning for persisted task records served by the protocol endpoints. */
public final class AgentProtocolConstants {

    private AgentProtocolConstants() {}

    /** Synthetic parent agent id for {@code agents/&lt;id&gt;/tasks/} paths. */
    public static final String PROTOCOL_AGENT_ID = "_agentscope_protocol";

    /** Single session bucket file holding all protocol tasks as a task-id map. */
    public static final String PROTOCOL_SESSION_ID = "_tasks";
}
