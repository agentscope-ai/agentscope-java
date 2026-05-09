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
package io.agentscope.harness.agent.sandbox.impl.e2b;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class E2bSnapshotRefsTest {

    @Test
    void roundTripSnapshotId() throws Exception {
        String id = "team/foo:v1";
        byte[] enc = E2bSnapshotRefs.encodeSnapshotId(id);
        assertArrayEquals(
                E2bSnapshotRefs.MAGIC_PREFIX,
                java.util.Arrays.copyOf(enc, E2bSnapshotRefs.MAGIC_PREFIX.length));
        assertEquals(id, E2bSnapshotRefs.decodeSnapshotIdIfPresent(enc));
    }

    @Test
    void decodeNonMagicReturnsNull() {
        assertNull(E2bSnapshotRefs.decodeSnapshotIdIfPresent("not-magic".getBytes()));
    }
}
