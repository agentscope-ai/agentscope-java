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
package io.agentscope.extensions.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import io.agentscope.core.state.AgentState;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OssAgentStateStoreTest {

    private OSS mockOss;
    private OssAgentStateStore store;

    @BeforeEach
    void setUp() {
        mockOss = mock(OSS.class);
        store =
                OssAgentStateStore.builder()
                        .ossClient(mockOss)
                        .bucketName("test-bucket")
                        .keyPrefix("test/state/")
                        .build();
    }

    @Test
    void builderRejectsNullClient() {
        assertThrows(
                NullPointerException.class,
                () -> OssAgentStateStore.builder().bucketName("b").build());
    }

    @Test
    void builderRejectsBlankBucket() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OssAgentStateStore.builder().ossClient(mockOss).bucketName("").build());
    }

    @Test
    void saveSingleState_putObjectCalled() {
        store.save("alice", "s1", "agent_state", new TestState("hello"));
        verify(mockOss)
                .putObject(
                        eq("test-bucket"),
                        eq("test/state/alice/s1/agent_state.json"),
                        any(InputStream.class));
    }

    @Test
    void getSingleState_returnsEmpty_whenNotExists() {
        when(mockOss.doesObjectExist("test-bucket", "test/state/alice/s1/agent_state.json"))
                .thenReturn(false);
        Optional<AgentState> result = store.get("alice", "s1", "agent_state", AgentState.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void getSingleState_returnsValue_whenExists() {
        String key = "test/state/alice/s1/agent_state.json";
        when(mockOss.doesObjectExist("test-bucket", key)).thenReturn(true);
        OSSObject obj = new OSSObject();
        obj.setObjectContent(
                new ByteArrayInputStream(
                        "{\"sessionId\":\"s1\"}".getBytes(StandardCharsets.UTF_8)));
        when(mockOss.getObject("test-bucket", key)).thenReturn(obj);

        Optional<AgentState> result = store.get("alice", "s1", "agent_state", AgentState.class);
        assertTrue(result.isPresent());
    }

    @Test
    void nullUserId_usesAnon() {
        store.save(null, "s1", "agent_state", new TestState("data"));
        verify(mockOss)
                .putObject(
                        eq("test-bucket"),
                        eq("test/state/__anon__/s1/agent_state.json"),
                        any(InputStream.class));
    }

    @Test
    void exists_returnsFalse_whenNoObjects() {
        ListObjectsV2Result result = mock(ListObjectsV2Result.class);
        when(result.getObjectSummaries()).thenReturn(List.of());
        when(mockOss.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(result);

        assertFalse(store.exists("alice", "s1"));
    }

    @Test
    void exists_returnsTrue_whenObjectsExist() {
        OSSObjectSummary summary = new OSSObjectSummary();
        summary.setKey("test/state/alice/s1/agent_state.json");
        ListObjectsV2Result result = mock(ListObjectsV2Result.class);
        when(result.getObjectSummaries()).thenReturn(List.of(summary));
        when(mockOss.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(result);

        assertTrue(store.exists("alice", "s1"));
    }

    @Test
    void listSessionIds_extractsIds() {
        OSSObjectSummary s1 = new OSSObjectSummary();
        s1.setKey("test/state/alice/sess-a/agent_state.json");
        OSSObjectSummary s2 = new OSSObjectSummary();
        s2.setKey("test/state/alice/sess-b/agent_state.json");

        ListObjectsV2Result result = mock(ListObjectsV2Result.class);
        when(result.getObjectSummaries()).thenReturn(List.of(s1, s2));
        when(result.isTruncated()).thenReturn(false);
        when(mockOss.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(result);

        Set<String> ids = store.listSessionIds("alice");
        assertEquals(Set.of("sess-a", "sess-b"), ids);
    }

    @Test
    void close_shutsDownClient() {
        store.close();
        verify(mockOss).shutdown();
    }

    record TestState(String data) implements io.agentscope.core.state.State {}
}
