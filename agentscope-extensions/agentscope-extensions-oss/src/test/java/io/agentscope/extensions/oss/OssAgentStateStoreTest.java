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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.AgentState;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

class OssAgentStateStoreTest {

    private S3Client mockS3;
    private OssAgentStateStore store;

    @BeforeEach
    void setUp() {
        mockS3 = mock(S3Client.class);
        store =
                OssAgentStateStore.builder()
                        .s3Client(mockS3)
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
                () -> OssAgentStateStore.builder().s3Client(mockS3).bucketName("").build());
    }

    @Test
    void saveSingleState_putObjectCalled() {
        store.save("alice", "s1", "agent_state", new TestState("hello"));
        verify(mockS3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void getSingleState_returnsEmpty_whenNotExists() {
        when(mockS3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("missing").build());
        Optional<AgentState> result = store.get("alice", "s1", "agent_state", AgentState.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void getSingleState_returnsValue_whenExists() {
        ResponseBytes<GetObjectResponse> bytes =
                ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().build(),
                        "{\"sessionId\":\"s1\"}".getBytes(StandardCharsets.UTF_8));
        when(mockS3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(bytes);

        Optional<AgentState> result = store.get("alice", "s1", "agent_state", AgentState.class);
        assertTrue(result.isPresent());
    }

    @Test
    void nullUserId_usesAnon() {
        store.save(null, "s1", "agent_state", new TestState("data"));
        verify(mockS3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void exists_returnsFalse_whenNoObjects() {
        when(mockS3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(List.of()).build());
        assertFalse(store.exists("alice", "s1"));
    }

    @Test
    void exists_returnsTrue_whenObjectsExist() {
        S3Object object = S3Object.builder().key("test/state/alice/s1/agent_state.json").build();
        when(mockS3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(List.of(object)).build());
        assertTrue(store.exists("alice", "s1"));
    }

    @Test
    void listSessionIds_extractsIds() {
        S3Object s1 = S3Object.builder().key("test/state/alice/sess-a/agent_state.json").build();
        S3Object s2 = S3Object.builder().key("test/state/alice/sess-b/agent_state.json").build();
        when(mockS3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(List.of(s1, s2)).build());

        Set<String> ids = store.listSessionIds("alice");
        assertEquals(Set.of("sess-a", "sess-b"), ids);
    }

    @Test
    void close_shutsDownClient() {
        store.close();
        verify(mockS3).close();
    }

    record TestState(String data) implements io.agentscope.core.state.State {}
}
