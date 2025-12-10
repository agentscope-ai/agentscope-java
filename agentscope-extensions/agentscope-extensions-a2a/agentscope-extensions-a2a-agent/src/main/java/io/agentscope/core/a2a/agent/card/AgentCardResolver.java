/*
 * Copyright 1999-2025 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

package io.agentscope.core.a2a.agent.card;

import io.a2a.spec.AgentCard;

/**
 * The Resolver for AgentCard of A2A.
 *
 * @author xiweng.yy
 */
public interface AgentCardResolver {

    /**
     * Produce AgentCard for target agent name.
     *
     * @param agentName name of agent
     * @return {@link AgentCard}
     */
    AgentCard getAgentCard(String agentName);
}
