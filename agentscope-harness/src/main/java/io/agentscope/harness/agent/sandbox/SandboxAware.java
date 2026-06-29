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

import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;

/**
 * Marks a filesystem that can have its backing {@link Sandbox} injected at runtime.
 *
 * <p>Implemented by {@link SandboxBackedFilesystem} so {@link
 * io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware} can bind and unbind a sandbox
 * per session key, allowing a single filesystem proxy to serve concurrent sessions safely.
 */
public interface SandboxAware {

    // Binds a live sandbox to the given session key for the duration of one call.
    void bindSandbox(String sessionKey, Sandbox sandbox);

    // Removes the sandbox binding for the given session key after a call completes.
    void unbindSandbox(String sessionKey);

    Sandbox getSandbox(String sessionKey);
}
