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
package io.agentscope.harness.agent.sandbox;

import java.time.Duration;

/**
 * Thrown when a {@link SandboxExecutionGuard} gives up waiting for a busy isolation slot before the
 * configured wait timeout elapses.
 *
 * <p>This is a backstop against a <em>wedged</em> permit holder (a call that acquired the slot but
 * never releases it — e.g. a stuck container). It is <em>not</em> a lock-contention timeout: a
 * healthy holder legitimately keeps the slot for a full agent call, so the timeout must be set well
 * above the realistic maximum call duration. The default guard waits indefinitely; a timeout only
 * applies when one is explicitly configured via {@link SandboxExecutionGuard#inProcess(Duration)}.
 */
public class SandboxExecutionTimeoutException extends RuntimeException {

    private final transient SandboxIsolationKey key;
    private final Duration waited;

    public SandboxExecutionTimeoutException(SandboxIsolationKey key, Duration waited) {
        super(
                "Timed out after "
                        + waited
                        + " waiting for sandbox execution slot "
                        + key
                        + "; the holder may be wedged, or the wait timeout is set too low for the"
                        + " call duration");
        this.key = key;
        this.waited = waited;
    }

    /** Returns the isolation key whose slot could not be acquired in time. */
    public SandboxIsolationKey getKey() {
        return key;
    }

    /** Returns the configured wait duration that elapsed before giving up. */
    public Duration getWaited() {
        return waited;
    }
}
