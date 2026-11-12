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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.BiConsumer;
import org.slf4j.LoggerFactory;

/** Best-effort owner wakeup; durable cancellation and bounded repair remain authoritative. */
public final class ManagedToolControlFanout implements AutoCloseable {
    private static final String CHANNEL = "tool-control-v1";
    private final RedisManagedInfrastructure redis;
    private final ObjectMapper mapper;
    private final BiConsumer<ManagedScope, String> local;
    private final AutoCloseable subscription;

    record Hint(ManagedScope scope, String task) {}

    public ManagedToolControlFanout(
            RedisManagedInfrastructure redis,
            ObjectMapper mapper,
            BiConsumer<ManagedScope, String> local) {
        this.redis = redis;
        this.mapper = mapper;
        this.local = local;
        subscription = redis.busListen(CHANNEL, this::receive);
    }

    public void wakeup(ManagedScope scope, String task) {
        local.accept(scope, task);
        try {
            redis.busPublish(CHANNEL, mapper.writeValueAsString(new Hint(scope, task)));
        } catch (Exception unavailable) {
            LoggerFactory.getLogger(getClass())
                    .warn("Tool control fanout unavailable; durable cancellation remains pending");
        }
    }

    private void receive(String payload) {
        try {
            var hint = mapper.readValue(payload, Hint.class);
            local.accept(hint.scope(), hint.task());
        } catch (Exception invalid) {
            LoggerFactory.getLogger(getClass()).debug("Invalid tool control hint");
        }
    }

    @Override
    public void close() throws Exception {
        if (subscription != null) subscription.close();
    }
}
