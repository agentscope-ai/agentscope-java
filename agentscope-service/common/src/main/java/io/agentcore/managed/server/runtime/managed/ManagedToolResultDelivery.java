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
package io.agentcore.managed.server.runtime.managed;

import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import java.time.Duration;
import java.util.Optional;

/** Independent result mailboxes; each waiter reads the same immutable result, never competes for it. */
public interface ManagedToolResultDelivery {
    void publish(ManagedScope scope, String taskId, Message message);

    Optional<Message> await(ManagedScope scope, String taskId, Duration block);

    static String key(ManagedScope scope, String taskId) {
        ManagedScope.requireText(taskId, "tool task id");
        return scope.key("tool-result:" + ManagedScope.fingerprint(taskId));
    }
}
