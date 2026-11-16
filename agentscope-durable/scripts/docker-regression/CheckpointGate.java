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
package io.agentscope.extensions.durable.jdbc;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CountDownLatch;

/** Injected only in the test JVM. Pause at an uncommitted checkpoint or after its commit. */
final class CheckpointGate {
    private static final String PHASE = System.getenv("CHECKPOINT_GATE");
    private static final ThreadLocal<Boolean> switching = new ThreadLocal<>();

    static void written(String part, JsonNode value) {
        if (PHASE == null || !part.endsWith(":checkpoint") || value == null) return;
        var pointer = value.path("inline");
        if (pointer.path("recoveryCursor").asLong()
                > Long.parseLong(System.getenv("BASE_CURSOR"))) {
            switching.set(true);
            stopAt("cursor");
        } else if (pointer.path("position").asLong()
                > Long.parseLong(System.getenv("BASE_POSITION"))) {
            stopAt("snapshot");
        }
    }

    static void committed() {
        if (Boolean.TRUE.equals(switching.get())) {
            switching.remove();
            stopAt("ack");
        }
    }

    private static void stopAt(String phase) {
        if (!phase.equals(PHASE)) return;
        System.out.println("CHECKPOINT_GATE " + phase);
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
