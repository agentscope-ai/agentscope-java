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

/**
 * Result of acquiring a {@link Sandbox} from {@link SandboxManager}.
 */
public final class SandboxAcquireResult {

    private final Sandbox sandbox;
    private final boolean sdkOwned;

    private SandboxAcquireResult(Sandbox sandbox, boolean sdkOwned) {
        this.sandbox = sandbox;
        this.sdkOwned = sdkOwned;
    }

    public static SandboxAcquireResult sdkOwned(Sandbox sandbox) {
        return new SandboxAcquireResult(sandbox, true);
    }

    public static SandboxAcquireResult developerOwned(Sandbox sandbox) {
        return new SandboxAcquireResult(sandbox, false);
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    public boolean isSdkOwned() {
        return sdkOwned;
    }
}
