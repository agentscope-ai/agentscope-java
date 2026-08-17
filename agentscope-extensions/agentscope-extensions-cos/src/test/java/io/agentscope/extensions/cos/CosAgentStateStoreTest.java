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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.DeleteObjectsRequest;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import io.agentscope.core.state.AgentState;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CosAgentStateStoreTest {

    private COSClient mockCos;
    private CosAgentStateStore store;

    @BeforeEach
    void setUp() {
        mockCos = mock(COSClient.class);
        store =
                CosAgentStateStore.builder()
                        .cosClient(mockCos)
                        .bucketName("test-bucket")
                        .keyPrefix("test/state/")
                        .build();
    }

    @Test
    void builderRejectsNullClient() {
        assertThrows(
                NullPointerException.class,
                () -> CosAgentStateStore.builder().bucketName("b").build());
    }

    @Test
    void builderRejectsBlankBucket() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CosAgentStateStore.builder().cosClient(mockCos).bucketName("").build());
    }

    @Test
    void saveSingleState_putObjectCalled() {
        store.save("alice", "s1", "agent_state", new TestState("hello"));
        verify(mockCos).putObject(any(PutObjectRequest.class));
    }

    @Test
    void saveSingleState_usesCorrectKey() {
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        store.save("alice", "s1", "agent_state", new TestState("hello"));
        verify(mockCos).putObject(captor.capture());
        assertEquals("test/state/alice/s1/agent_state.json", captor.getValue().getKey());
    }

    @Test
    void getSingleState_returnsEmpty_whenNotExists() {
        notFound("test/state/alice/s1/agent_state.json");

        Optional<AgentState> result = store.get("alice", "s1", "agent_state", AgentState.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void getSingleState_returnsValue_whenExists() {
        String key = "test/state/alice/s1/agent_state.json";
        when(mockCos.getObjectMetadata("test-bucket", key)).thenReturn(new ObjectMetadata());
        when(mockCos.getObject(any(GetObjectRequest.class)))
                .thenReturn(cosObjectWith("{\"sessionId\":\"s1\"}"));

        Optional<AgentState> result = store.get("alice", "s1", "agent_state", AgentState.class);
        assertTrue(result.isPresent());
    }

    @Test
    void nullUserId_usesAnon() {
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        store.save(null, "s1", "agent_state", new TestState("data"));
        verify(mockCos).putObject(captor.capture());
        assertEquals("test/state/__anon__/s1/agent_state.json", captor.getValue().getKey());
    }

    @Test
    void exists_returnsFalse_whenNoObjects() {
        ObjectListing result = mock(ObjectListing.class);
        when(result.getObjectSummaries()).thenReturn(List.of());
        when(mockCos.listObjects(any(ListObjectsRequest.class))).thenReturn(result);

        assertFalse(store.exists("alice", "s1"));
    }

    @Test
    void exists_returnsTrue_whenObjectsExist() {
        COSObjectSummary summary = new COSObjectSummary();
        summary.setKey("test/state/alice/s1/agent_state.json");
        ObjectListing result = mock(ObjectListing.class);
        when(result.getObjectSummaries()).thenReturn(List.of(summary));
        when(mockCos.listObjects(any(ListObjectsRequest.class))).thenReturn(result);

        assertTrue(store.exists("alice", "s1"));
    }

    @Test
    void listSessionIds_extractsIds() {
        COSObjectSummary s1 = new COSObjectSummary();
        s1.setKey("test/state/alice/sess-a/agent_state.json");
        COSObjectSummary s2 = new COSObjectSummary();
        s2.setKey("test/state/alice/sess-b/agent_state.json");

        ObjectListing result = mock(ObjectListing.class);
        when(result.getObjectSummaries()).thenReturn(List.of(s1, s2));
        when(result.isTruncated()).thenReturn(false);
        when(mockCos.listObjects(any(ListObjectsRequest.class))).thenReturn(result);

        Set<String> ids = store.listSessionIds("alice");
        assertEquals(Set.of("sess-a", "sess-b"), ids);
    }

    @Test
    void saveList_state_writesListAndHash() {
        String listKey = "test/state/alice/s1/agent_state.list.json";
        String hashKey = "test/state/alice/s1/agent_state.list.hash";
        notFound(hashKey);

        store.save("alice", "s1", "agent_state", List.of(new TestState("a"), new TestState("b")));

        // putString called for list json + hash
        verify(mockCos, times(2)).putObject(any(PutObjectRequest.class));
    }

    @Test
    void saveList_state_rewritesWhenHashMismatch() {
        String listKey = "test/state/alice/s1/agent_state.list.json";
        String hashKey = "test/state/alice/s1/agent_state.list.hash";
        when(mockCos.getObjectMetadata("test-bucket", hashKey)).thenReturn(new ObjectMetadata());
        when(mockCos.getObject(any(GetObjectRequest.class)))
                .thenAnswer(
                        inv -> {
                            GetObjectRequest req = inv.getArgument(0);
                            String k = req.getKey();
                            if (k.equals(hashKey)) return cosObjectWith("oldhash");
                            if (k.equals(listKey)) return cosObjectWith("[{\"data\":\"a\"}]");
                            return cosObjectWith("");
                        });

        store.save("alice", "s1", "agent_state", List.of(new TestState("x")));

        // full rewrite because hash differs: needsFullRewrite returns true
        verify(mockCos, times(2)).putObject(any(PutObjectRequest.class));
    }

    @Test
    void getList_returnsEmpty_whenNotExists() {
        notFound("test/state/alice/s1/agent_state.list.json");

        List<TestState> result = store.getList("alice", "s1", "agent_state", TestState.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void getList_returnsDeserializedList() {
        String listKey = "test/state/alice/s1/agent_state.list.json";
        when(mockCos.getObjectMetadata("test-bucket", listKey)).thenReturn(new ObjectMetadata());
        when(mockCos.getObject(any(GetObjectRequest.class)))
                .thenReturn(cosObjectWith("[{\"data\":\"a\"},{\"data\":\"b\"}]"));

        List<TestState> result = store.getList("alice", "s1", "agent_state", TestState.class);
        assertEquals(2, result.size());
        assertEquals("a", result.get(0).data());
        assertEquals("b", result.get(1).data());
    }

    @Test
    void deleteSession_removesAllKeys() {
        String prefix = "test/state/alice/s1/";
        COSObjectSummary s1 = new COSObjectSummary();
        s1.setKey("test/state/alice/s1/k1.json");
        COSObjectSummary s2 = new COSObjectSummary();
        s2.setKey("test/state/alice/s1/k2.json");
        ObjectListing result = mock(ObjectListing.class);
        when(result.getObjectSummaries()).thenReturn(List.of(s1, s2));
        when(result.isTruncated()).thenReturn(false);
        when(mockCos.listObjects(any(ListObjectsRequest.class))).thenReturn(result);

        store.delete("alice", "s1");

        verify(mockCos).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void deleteSingleKey_removesStateListAndHash() {
        String stateKey = "test/state/alice/s1/k1.json";
        String listKey = "test/state/alice/s1/k1.list.json";
        String hashKey = "test/state/alice/s1/k1.list.hash";
        when(mockCos.getObjectMetadata("test-bucket", stateKey)).thenReturn(new ObjectMetadata());
        when(mockCos.getObjectMetadata("test-bucket", listKey)).thenReturn(new ObjectMetadata());
        when(mockCos.getObjectMetadata("test-bucket", hashKey)).thenReturn(new ObjectMetadata());

        store.delete("alice", "s1", "k1");

        verify(mockCos).deleteObject("test-bucket", stateKey);
        verify(mockCos).deleteObject("test-bucket", listKey);
        verify(mockCos).deleteObject("test-bucket", hashKey);
    }

    @Test
    void deleteSingleKey_skipsMissingKeys() {
        String stateKey = "test/state/alice/s1/k1.json";
        notFound("test/state/alice/s1/k1.list.json");
        notFound("test/state/alice/s1/k1.list.hash");

        store.delete("alice", "s1", "k1");

        verify(mockCos).deleteObject("test-bucket", stateKey);
        verify(mockCos, never()).deleteObject("test-bucket", "test/state/alice/s1/k1.list.json");
        verify(mockCos, never()).deleteObject("test-bucket", "test/state/alice/s1/k1.list.hash");
    }

    @Test
    void listSessionIds_returnsEmpty_whenNoKeys() {
        ObjectListing result = mock(ObjectListing.class);
        when(result.getObjectSummaries()).thenReturn(List.of());
        when(mockCos.listObjects(any(ListObjectsRequest.class))).thenReturn(result);

        Set<String> ids = store.listSessionIds("nobody");
        assertTrue(ids.isEmpty());
    }

    @Test
    void builderWithCustomPrefix() {
        CosAgentStateStore custom =
                CosAgentStateStore.builder()
                        .cosClient(mockCos)
                        .bucketName("b")
                        .keyPrefix("custom/")
                        .build();
        assertNotNull(custom);
    }

    @Test
    void close_shutsDownClient() {
        store.close();
        verify(mockCos).shutdown();
    }

    // ---- helpers ----

    private void notFound(String key) {
        CosServiceException ex = new CosServiceException("not found");
        ex.setStatusCode(404);
        when(mockCos.getObjectMetadata("test-bucket", key)).thenThrow(ex);
    }

    private static COSObject cosObjectWith(String content) {
        COSObject obj = new COSObject();
        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        org.apache.http.client.methods.HttpGet req =
                mock(org.apache.http.client.methods.HttpGet.class);
        obj.setObjectContent(new COSObjectInputStream(is, req));
        return obj;
    }

    record TestState(String data) implements io.agentscope.core.state.State {}
}
