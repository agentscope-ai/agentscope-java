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

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Internal per-call lazy acquisition shared by shallow RuntimeContext copies. The monitor
 * serializes first access with release, so cancellation cannot orphan a late acquisition.
 */
public final class LazySandboxBinding {
    private final Supplier<SandboxAcquireResult> acquire;
    private SandboxAcquireResult result;
    private RuntimeException failure;
    private boolean initializing;
    private boolean released;

    public LazySandboxBinding(Supplier<SandboxAcquireResult> acquire) {
        this.acquire = Objects.requireNonNull(acquire);
    }

    public synchronized SandboxAcquireResult get() {
        if (released) {
            throw new SandboxException.SandboxConfigurationException(
                    "Sandbox acquisition has been released for this call");
        }
        if (failure != null) {
            throw failure;
        }
        if (result == null) {
            if (initializing) {
                throw new SandboxException.SandboxConfigurationException(
                        "Sandbox access during its initialization is not supported");
            }
            initializing = true;
            try {
                result = Objects.requireNonNull(acquire.get());
            } catch (RuntimeException error) {
                failure = error;
                throw error;
            } finally {
                initializing = false;
            }
        }
        return result;
    }

    /** Closes this binding and hands its acquired resource to the lifecycle owner exactly once. */
    public synchronized SandboxAcquireResult release() {
        if (released) {
            return null;
        }
        released = true;
        return result;
    }
}
