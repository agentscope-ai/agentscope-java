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

/** A durable tool completion notification, independent of when Brain captures a suspension. */
public record ManagedToolResume(
        int version,
        ManagedScope scope,
        String notificationId,
        String turnId,
        String taskId,
        String executionId,
        String resultHash,
        long finalSequence) {
    public ManagedToolResume {
        if (version != 1 || scope == null || finalSequence < 1)
            throw new IllegalArgumentException("Invalid tool Resume");
        ManagedScope.requireText(notificationId, "notification id");
        ManagedScope.requireText(turnId, "turn id");
        ManagedScope.requireText(taskId, "task id");
        ManagedScope.requireText(executionId, "execution id");
        ManagedScope.requireText(resultHash, "result hash");
    }

    public String messageGroup() {
        return ManagedScope.fingerprint(scope.messageGroup(), taskId).substring(0, 48);
    }
}
