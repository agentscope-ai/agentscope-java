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
package io.agentscope.core.agent;

import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Interface for agents that can observe messages without generating replies.
 *
 * <p>This interface enables agents to receive and process messages from other agents
 * or the environment without responding. It's commonly used in multi-agent collaboration
 * scenarios where agents need to be aware of each other's actions.
 *
 * <p>Use cases include:
 * <ul>
 *   <li>Passive monitoring of conversation flow</li>
 *   <li>Building shared context in multi-agent systems</li>
 *   <li>Implementing observer patterns in agent pipelines</li>
 * </ul>
 */
public interface ObservableAgent {

    /**
     * Observe a single message without generating a reply.
     *
     * @param msg The message to observe
     * @return Mono that completes when observation is done
     */
    Mono<Void> observe(Msg msg);

    /**
     * Observe multiple messages without generating a reply.
     *
     * @param msgs The messages to observe
     * @return Mono that completes when all observations are done
     */
    Mono<Void> observe(List<Msg> msgs);
}
