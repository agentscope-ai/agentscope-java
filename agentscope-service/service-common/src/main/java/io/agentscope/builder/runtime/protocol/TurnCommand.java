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
package io.agentscope.builder.runtime.protocol;

import java.util.Map;
import java.util.Objects;

/** A durable submission; acknowledgement means persisted, not started. */
public record TurnCommand(
        RuntimeRequestContext context,
        String idempotencyKey,
        String inputDigest,
        long admissionSequence,
        Map<String, Object> input) {
    public TurnCommand {
        Objects.requireNonNull(context, "context");
        RuntimeProtocol.requireId(context.turnId(), "turnId");
        RuntimeProtocol.requireId(idempotencyKey, "idempotencyKey");
        RuntimeProtocol.requireId(inputDigest, "inputDigest");
        if (admissionSequence <= 0)
            throw new IllegalArgumentException("admissionSequence must be positive");
        input = RuntimeProtocol.freeze(input);
    }
}
