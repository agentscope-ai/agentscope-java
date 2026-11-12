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

import java.time.Duration;
import java.util.Optional;

/** Ephemeral mutual exclusion only; durable execution epochs remain in the database. */
public interface ManagedLeaseStore {
    record Lease(String key, String token) {
        public Lease {
            ManagedScope.requireText(key, "lease key");
            ManagedScope.requireText(token, "lease token");
        }
    }

    Optional<Lease> acquire(String key, Duration ttl);

    boolean renew(Lease lease, Duration ttl);

    boolean owns(Lease lease);

    boolean release(Lease lease);
}
