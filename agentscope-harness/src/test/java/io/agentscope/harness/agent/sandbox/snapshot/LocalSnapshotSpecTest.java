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
package io.agentscope.harness.agent.sandbox.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LocalSnapshotSpecTest {

    @Test
    void build_createsLocalSandboxSnapshotWithCorrectId() {
        LocalSnapshotSpec spec = new LocalSnapshotSpec("/tmp/snapshots");

        SandboxSnapshot snapshot = spec.build("my-session-id");

        assertEquals("my-session-id", snapshot.getId());
        assertEquals("local", snapshot.getType());
    }
}
