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
package io.agentscope.examples.harness.sandbox.support;

import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxStateStore;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link SandboxStateStore} for examples: simulates a shared Redis-style slot map so
 * multiple {@link io.agentscope.harness.agent.HarnessAgent} instances can resume the same user's
 * sandbox metadata.
 */
public final class SharedInMemorySandboxStateStore implements SandboxStateStore {

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    private static String mapKey(SandboxIsolationKey key) {
        return key.getScope().name() + ":" + key.getValue();
    }

    @Override
    public Optional<String> load(SandboxIsolationKey key) throws IOException {
        String v = store.get(mapKey(key));
        return Optional.ofNullable(v);
    }

    @Override
    public void save(SandboxIsolationKey key, String json) throws IOException {
        store.put(mapKey(key), json);
    }

    @Override
    public void delete(SandboxIsolationKey key) throws IOException {
        store.remove(mapKey(key));
    }
}
