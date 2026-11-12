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
package io.agentcore.managed.server.runtime.managed;

import io.agentcore.managed.server.runtime.managed.ManagedTurnPublisher.Command;
import java.time.Duration;
import java.util.List;

/** Private ACK capabilities; Service owns durability, the consumer owns broker RPC. */
public interface ManagedTurnAckLedger {
    record Pending(String receiptId, String scopeKey, String turnId, RocketMqTurnReceipt receipt) {}

    void capture(
            String topic,
            String group,
            Command command,
            RocketMqTurnReceipt receipt,
            Duration delay);

    List<Pending> claim(String topic, String group, int limit);

    void complete(String topic, String group, String receiptId);
}
