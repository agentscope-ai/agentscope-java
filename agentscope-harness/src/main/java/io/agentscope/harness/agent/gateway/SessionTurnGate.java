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
package io.agentscope.harness.agent.gateway;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Fair per-key mutual exclusion for gateway turns (user/channel inbound runs and subagent
 * notification).
 */
public final class SessionTurnGate {

    private final ConcurrentHashMap<String, Semaphore> gates = new ConcurrentHashMap<>();

    public void acquire(String key) throws InterruptedException {
        gates.computeIfAbsent(key, k -> new Semaphore(1, true)).acquire();
    }

    public void release(String key) {
        Semaphore s = gates.get(key);
        if (s != null) {
            s.release();
        }
    }
}
