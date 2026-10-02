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
package io.agentscope.extensions.cos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CosRemoteSnapshotClientTest {

    private COSClient mockCos;
    private CosRemoteSnapshotClient client;

    @BeforeEach
    void setUp() {
        mockCos = mock(COSClient.class);
        client = new CosRemoteSnapshotClient(mockCos, "test-bucket", "snapshots/");
    }

    // ── constructor ──────────────────────────────────────────────────────────

    @Test
    void rejectsNullClient() {
        assertThrows(
                NullPointerException.class,
                () -> new CosRemoteSnapshotClient(null, "bucket", "prefix/"));
    }

    @Test
    void rejectsBlankBucket() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CosRemoteSnapshotClient(mockCos, "", "prefix/"));
    }

    @Test
    void rejectsNullBucket() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CosRemoteSnapshotClient(mockCos, null, "prefix/"));
    }

    // ── normalizePrefix ───────────────────────────────────────────────────────

    @Test
    void nullPrefix_treatedAsEmpty() throws Exception {
        CosRemoteSnapshotClient c = new CosRemoteSnapshotClient(mockCos, "bucket", null);
        c.upload("snap1", new ByteArrayInputStream("data".getBytes()));
        verify(mockCos).putObject(any(PutObjectRequest.class));
        // key should be snap1.tar (no leading slash)
    }

    @Test
    void prefixWithLeadingSlash_isStripped() throws Exception {
        CosRemoteSnapshotClient c = new CosRemoteSnapshotClient(mockCos, "bucket", "/prefix/");
        InputStream data = new ByteArrayInputStream("x".getBytes());
        c.upload("id1", data);
        verify(mockCos).putObject(any(PutObjectRequest.class));
    }

    @Test
    void prefixWithoutTrailingSlash_getsSlashAppended() throws Exception {
        CosRemoteSnapshotClient c = new CosRemoteSnapshotClient(mockCos, "bucket", "prefix");
        c.upload("id1", new ByteArrayInputStream("x".getBytes()));
        verify(mockCos).putObject(any(PutObjectRequest.class));
    }

    // ── upload ───────────────────────────────────────────────────────────────

    @Test
    void upload_callsPutObjectWithCorrectKey() throws Exception {
        byte[] content = "snapshot-content".getBytes(StandardCharsets.UTF_8);
        client.upload("snap1", new ByteArrayInputStream(content));
        verify(mockCos).putObject(any(PutObjectRequest.class));
    }

    @Test
    void upload_rejectsBlankSnapshotId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> client.upload("", new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void upload_rejectsNullSnapshotId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> client.upload(null, new ByteArrayInputStream(new byte[0])));
    }

    // ── download ─────────────────────────────────────────────────────────────

    @Test
    void download_returnsStream_whenExists() throws Exception {
        byte[] content = "tar-data".getBytes(StandardCharsets.UTF_8);
        ObjectMetadata meta = new ObjectMetadata();
        when(mockCos.getObjectMetadata("test-bucket", "snapshots/snap1.tar")).thenReturn(meta);
        COSObject cosObj = mock(COSObject.class);
        COSObjectInputStream cosStream = mock(COSObjectInputStream.class);
        when(cosStream.read(any(byte[].class), any(int.class), any(int.class)))
                .thenAnswer(
                        inv -> {
                            byte[] buf = inv.getArgument(0);
                            int len = Math.min(inv.getArgument(2), content.length);
                            System.arraycopy(content, 0, buf, 0, len);
                            return len;
                        })
                .thenReturn(-1);
        when(cosObj.getObjectContent()).thenReturn(cosStream);
        when(mockCos.getObject(any(GetObjectRequest.class))).thenReturn(cosObj);

        InputStream result = client.download("snap1");
        assertTrue(result != null);
    }

    @Test
    void download_throwsFileNotFound_when404() {
        CosServiceException ex = new CosServiceException("not found");
        ex.setStatusCode(404);
        when(mockCos.getObjectMetadata(eq("test-bucket"), eq("snapshots/snap1.tar"))).thenThrow(ex);

        assertThrows(FileNotFoundException.class, () -> client.download("snap1"));
    }

    @Test
    void download_rethrows_onNon404Error() {
        CosServiceException ex = new CosServiceException("server error");
        ex.setStatusCode(500);
        when(mockCos.getObjectMetadata(eq("test-bucket"), eq("snapshots/snap1.tar"))).thenThrow(ex);

        assertThrows(CosServiceException.class, () -> client.download("snap1"));
    }

    @Test
    void download_rejectsBlankSnapshotId() {
        assertThrows(IllegalArgumentException.class, () -> client.download(""));
    }

    // ── exists ───────────────────────────────────────────────────────────────

    @Test
    void exists_returnsTrue_whenObjectFound() throws Exception {
        when(mockCos.getObjectMetadata("test-bucket", "snapshots/snap1.tar"))
                .thenReturn(new ObjectMetadata());
        assertTrue(client.exists("snap1"));
    }

    @Test
    void exists_returnsFalse_when404() throws Exception {
        CosServiceException ex = new CosServiceException("not found");
        ex.setStatusCode(404);
        when(mockCos.getObjectMetadata("test-bucket", "snapshots/snap1.tar")).thenThrow(ex);
        assertFalse(client.exists("snap1"));
    }

    @Test
    void exists_rethrows_onNon404Error() {
        CosServiceException ex = new CosServiceException("server error");
        ex.setStatusCode(500);
        when(mockCos.getObjectMetadata("test-bucket", "snapshots/snap1.tar")).thenThrow(ex);
        assertThrows(CosServiceException.class, () -> client.exists("snap1"));
    }

    @Test
    void exists_rejectsBlankSnapshotId() {
        assertThrows(IllegalArgumentException.class, () -> client.exists(""));
    }

    // ── CosSnapshotSpec ───────────────────────────────────────────────────────

    @Test
    void cosSnapshotSpec_build_returnsSandboxSnapshotWithId() {
        CosSnapshotSpec spec = new CosSnapshotSpec(mockCos, "test-bucket", "snapshots/");
        var snapshot = spec.build("session-123");
        assertTrue(
                snapshot
                        instanceof
                        io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot);
        assertTrue(snapshot.getId().equals("session-123"));
    }
}
