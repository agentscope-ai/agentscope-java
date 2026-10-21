/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.runtime.managed;

import java.util.Map;
import java.util.Set;

/** HTTPS boundary for external Environment Workers; no internal infrastructure credentials. */
public final class ManagedWorkerProtocol {
    private ManagedWorkerProtocol() {}

    public record Claim(String workerId, int limit) {
        public Claim {
            ManagedScope.requireText(workerId, "Worker id");
            if (workerId.length() > 256 || limit < 1 || limit > 32)
                throw new IllegalArgumentException("Worker claim limit must be 1..32");
        }
    }

    public record Lease(
            ManagedScope scope, String taskId, String executionId, String token, String workerId) {
        public Lease {
            if (scope == null) throw new IllegalArgumentException("Worker scope required");
            ManagedScope.requireText(taskId, "task id");
            ManagedScope.requireText(executionId, "execution id");
            ManagedScope.requireText(token, "Worker token");
            ManagedScope.requireText(workerId, "Worker id");
        }
    }

    public record Task(
            String id, String turnId, String name, String arguments, String processHandle) {}

    public record Assignment(
            Lease lease,
            Task task,
            boolean firstLaunch,
            Map<String, Object> sandboxConfig,
            Map<String, String> definitionFiles) {}

    public record Result(Lease lease, String status, String result) {
        public Result {
            if (lease == null
                    || result == null
                    || !Set.of("succeeded", "failed", "cancelled").contains(status))
                throw new IllegalArgumentException("Invalid Worker result");
        }
    }

    public record Output(Lease lease, ManagedToolExecutor.OutputPage page) {
        public Output {
            if (lease == null || page == null)
                throw new IllegalArgumentException("Worker output required");
        }
    }
}
