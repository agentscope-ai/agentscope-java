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
package io.agentscope.durable;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Worker-side lease and commit operations for the model ledger. */
public interface ModelExecutionStore extends ModelExecutions {
    record Lease(Request request, String workerId, long epoch) {}

    List<Key> ready(String route, int limit);

    Optional<Lease> claim(Key key, String workerId, Duration duration);

    void renew(Lease lease, Duration duration);

    /** Records that the provider may have been called. A lease can begin only once. */
    void begin(Lease lease);

    void append(Lease lease, Batch batch);

    void complete(Lease lease, Outcome outcome);

    default Wakeups wakeups() {
        return Wakeups.none();
    }
}
