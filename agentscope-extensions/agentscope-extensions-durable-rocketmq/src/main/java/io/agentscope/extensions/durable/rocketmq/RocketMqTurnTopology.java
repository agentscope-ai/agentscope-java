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
package io.agentscope.extensions.durable.rocketmq;

record RocketMqTurnTopology(
        String dispatch,
        String signal,
        String ownerSignal,
        String runnerGroup,
        String signalGroup,
        String ownerGroup) {
    static RocketMqTurnTopology namespace(String namespace) {
        String prefix = RocketMqExecutionTopology.component(namespace, "RocketMQ Turn namespace");
        return new RocketMqTurnTopology(
                prefix + "-turn-dispatch",
                prefix + "-turn-signal",
                prefix + "-turn-owner",
                prefix + "-turn-runner",
                prefix + "-turn-signal",
                prefix + "-turn-owner");
    }
}
