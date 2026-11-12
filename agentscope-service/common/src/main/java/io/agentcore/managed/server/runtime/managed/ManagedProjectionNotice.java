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

import java.util.List;

/** Reliable fact delivery. ACK is allowed only after the query backend publishes this prefix. */
public record ManagedProjectionNotice(int schemaVersion, ManagedScope scope, List<Commit> commits) {
    public record Commit(String id, String hash, ManagedProjectionFacts.Manifest manifest) {
        public Commit {
            if (id == null
                    || id.isBlank()
                    || hash == null
                    || manifest == null
                    || !hash.equals(ManagedProjectionFacts.identity(manifest)))
                throw new IllegalArgumentException("Invalid projection commit identity");
        }
    }

    public ManagedProjectionNotice {
        if (schemaVersion != 3
                || scope == null
                || commits == null
                || commits.isEmpty()
                || commits.size() > 32)
            throw new IllegalArgumentException("Invalid projection delivery");
        commits = List.copyOf(commits);
        long version = commits.get(0).manifest().version() - 1;
        long sequence = commits.get(0).manifest().first() - 1;
        long bytes = 0;
        for (var commit : commits) {
            var m = commit.manifest();
            if (m.version() != ++version
                    || m.first() != sequence + 1
                    || m.entries().isEmpty()
                    || m.through() != m.first() + m.entries().size() - 1)
                throw new IllegalArgumentException("Non-contiguous projection delivery");
            bytes = Math.addExact(bytes, ManagedProjectionFacts.deliveryBytes(m));
            if (bytes > 64L * 1024 * 1024)
                throw new IllegalArgumentException("Projection batch exceeds byte budget");
            sequence = m.through();
        }
    }

    public long commitVersion() {
        return commits.get(commits.size() - 1).manifest().version();
    }

    public long through() {
        return commits.get(commits.size() - 1).manifest().through();
    }
}
