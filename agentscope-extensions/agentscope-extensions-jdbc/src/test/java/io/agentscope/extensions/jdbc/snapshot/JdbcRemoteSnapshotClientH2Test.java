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
package io.agentscope.extensions.jdbc.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.jdbc.H2TestSupport;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H2 in-memory integration tests for {@link JdbcRemoteSnapshotClient}.
 *
 * @author shanhongyu
 */
@DisplayName("JdbcRemoteSnapshotClient H2 integration tests")
class JdbcRemoteSnapshotClientH2Test {

    private JdbcRemoteSnapshotClient client;

    @BeforeEach
    void setUp() {
        DataSource ds = H2TestSupport.createDataSource("snapshot_test");
        client = new JdbcRemoteSnapshotClient(ds, new H2Dialect());
    }

    @Test
    @DisplayName("upload and download round-trips binary data")
    void uploadDownloadRoundTrip() throws Exception {
        byte[] data = "workspace tar archive content".getBytes();
        client.upload("snap-1", new ByteArrayInputStream(data));

        try (InputStream downloaded = client.download("snap-1")) {
            assertArrayEquals(data, downloaded.readAllBytes());
        }
    }

    @Test
    @DisplayName("upload is idempotent — overwrites existing snapshot")
    void uploadOverwritesExisting() throws Exception {
        client.upload("snap-1", new ByteArrayInputStream("old".getBytes()));
        client.upload("snap-1", new ByteArrayInputStream("new".getBytes()));

        try (InputStream downloaded = client.download("snap-1")) {
            assertArrayEquals("new".getBytes(), downloaded.readAllBytes());
        }
    }

    @Test
    @DisplayName("exists returns true after upload")
    void existsTrueAfterUpload() throws Exception {
        client.upload("snap-1", new ByteArrayInputStream(new byte[] {1, 2, 3}));
        assertTrue(client.exists("snap-1"));
    }

    @Test
    @DisplayName("exists returns false for missing snapshot")
    void existsFalseForMissing() throws Exception {
        assertFalse(client.exists("nonexistent"));
    }

    @Test
    @DisplayName("download throws FileNotFoundException for missing snapshot")
    void downloadMissingThrows() throws Exception {
        assertThrows(FileNotFoundException.class, () -> client.download("missing"));
    }

    @Test
    @DisplayName("download large binary data round-trips correctly")
    void downloadLargeBinary() throws Exception {
        byte[] large = new byte[10_000];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 256);
        }
        client.upload("large-snap", new ByteArrayInputStream(large));

        try (InputStream downloaded = client.download("large-snap")) {
            assertArrayEquals(large, downloaded.readAllBytes());
        }
    }
}
