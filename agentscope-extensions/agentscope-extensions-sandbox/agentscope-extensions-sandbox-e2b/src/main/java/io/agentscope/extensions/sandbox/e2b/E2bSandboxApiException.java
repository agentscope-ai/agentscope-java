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
package io.agentscope.extensions.sandbox.e2b;

import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;

/** Exposes a conclusive provider status without inferring absence from transport failures. */
public final class E2bSandboxApiException extends SandboxException.SandboxRuntimeException {
    private final int status;

    public E2bSandboxApiException(int status, String message) {
        super(SandboxErrorCode.WORKSPACE_START_ERROR, message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
