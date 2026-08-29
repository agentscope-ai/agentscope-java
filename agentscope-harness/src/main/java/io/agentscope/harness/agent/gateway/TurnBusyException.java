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

/**
 * Thrown when a session turn gate cannot be acquired because another turn holds the lock.
 *
 * <p>{@link HarnessGateway} maps this to an empty {@link reactor.core.publisher.Mono} so duplicate
 * wakeup or concurrent inbound requests for the same session are skipped rather than queued.
 */
public final class TurnBusyException extends Exception {

    private final String gateKey;

    /**
     * Creates an exception for the given gate key.
     *
     * @param gateKey the canonical session gate key that is busy
     */
    public TurnBusyException(String gateKey) {
        super("Session turn gate busy: " + gateKey);
        this.gateKey = gateKey;
    }

    /**
     * Returns the gate key that could not be acquired.
     *
     * @return the busy gate key
     */
    public String getGateKey() {
        return gateKey;
    }
}
