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

import io.agentscope.core.session.SessionLogException;

/** Internal execution yield, never a successful tool result or an external user interaction. */
public final class ManagedToolWaiting extends SessionLogException {
    private final String taskId;

    public ManagedToolWaiting(String taskId) {
        super("Inline tool wait expired; the original Hands operation remains active");
        this.taskId = taskId;
    }

    public String taskId() {
        return taskId;
    }

    public static ManagedToolWaiting find(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof ManagedToolWaiting waiting) return waiting;
        return null;
    }
}
