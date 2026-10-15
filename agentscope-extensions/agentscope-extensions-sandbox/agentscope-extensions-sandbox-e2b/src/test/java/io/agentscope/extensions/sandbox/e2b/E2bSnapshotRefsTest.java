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
package io.agentscope.extensions.sandbox.e2b;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
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

    @Test
    void peekSnapshotIdDecodesFromStream() throws Exception {
        byte[] archive = E2bSnapshotRefs.encodeSnapshotId("team/foo:v1");
        E2bSnapshotRefs.SnapshotIdRef ref =
                E2bSnapshotRefs.peekSnapshotId(new ByteArrayInputStream(archive));
        assertEquals("team/foo:v1", ref.snapshotId());
        assertArrayEquals(archive, ref.archive());
    }

    @Test
    void peekSnapshotIdRejectsMalformedNativeArchive() {
        byte[] archive =
                Arrays.copyOf(
                        E2bSnapshotRefs.MAGIC_PREFIX, E2bSnapshotRefs.MAGIC_PREFIX.length + 2);
        assertThrows(
                IOException.class,
                () -> E2bSnapshotRefs.peekSnapshotId(new ByteArrayInputStream(archive)));
    }
}
