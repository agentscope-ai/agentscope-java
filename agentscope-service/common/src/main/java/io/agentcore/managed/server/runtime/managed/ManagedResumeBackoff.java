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
import java.util.LinkedHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Republishing the same logical Resume resets broker attempts, so contention backoff is local. */
final class ManagedResumeBackoff {
    private record Attempt(int count, long touched) {}

    private final LinkedHashMap<String, Attempt> attempts = new LinkedHashMap<>(16, .75f, true);

    synchronized Duration next(String key, Duration minimum) {
        long now = System.nanoTime();
        var previous = attempts.get(key);
        int count =
                previous == null || now - previous.touched() > Duration.ofMinutes(5).toNanos()
                        ? 0
                        : Math.min(previous.count() + 1, 5);
        attempts.put(key, new Attempt(count, now));
        while (attempts.size() > 16384) attempts.remove(attempts.keySet().iterator().next());
        long ceiling = Math.min(10_000, 500L << count);
        long jittered =
                count == 0 ? 500 : ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1);
        return Duration.ofMillis(Math.max(minimum.toMillis(), jittered));
    }

    synchronized void clear(String key) {
        attempts.remove(key);
    }
}
