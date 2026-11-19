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
package io.agentscope.extensions.agentprotocol;

import java.time.Instant;

/**
 * One held execution lease, as returned by {@link ProtocolTaskRunRegistry#tryAcquire}.
 *
 * <p>A shared {@link ProtocolTaskRunRegistry} implementation must be able to persist this value: the
 * usual Redis encoding is {@code {ownerId}:{leaseId}} as the value of the task key, with
 * {@code expiresAt} carried by the key's TTL rather than stored explicitly.
 *
 * @param taskId task the lease belongs to
 * @param ownerId identifier of the instance holding it
 * @param leaseId identifier of this acquisition; guards {@link ProtocolTaskRunRegistry#release} and
 *     {@link ProtocolTaskRunRegistry#renew} against fencing by a later acquisition of the same task
 * @param expiresAt instant after which the lease is considered abandoned
 */
public record ProtocolTaskLease(String taskId, String ownerId, String leaseId, Instant expiresAt) {

    public ProtocolTaskLease {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("ownerId must not be null");
        }
        if (leaseId == null || leaseId.isBlank()) {
            throw new IllegalArgumentException("leaseId must not be blank");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }
    }
}
