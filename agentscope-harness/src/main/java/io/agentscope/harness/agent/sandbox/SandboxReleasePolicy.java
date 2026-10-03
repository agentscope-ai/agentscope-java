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

/** Policy for releasing SDK-managed sandboxes after an agent call. */
public enum SandboxReleasePolicy {
    /** Persist the workspace and destroy the sandbox (the compatibility default). */
    DELETE,
    /**
     * Persist the workspace and retain the sandbox for reuse until provider expiry or explicit
     * deletion. Requires backend support and a single writer per isolation key. Retaining a live
     * sandbox does not replace configuring a snapshot store for recovery after expiry.
     */
    RETAIN
}
