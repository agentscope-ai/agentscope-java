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

package io.agentscope.core.training.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.training.backend.dto.CommitRequest;
import io.agentscope.core.training.backend.dto.FeedbackRequest;
import io.agentscope.core.training.util.TrainingTestConstants;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@DisplayName("TrinityClient Unit Tests")
class TrinityClientTest {

    private MockWebServer mockServer;
    private TrinityClient client;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String baseUrl = mockServer.url("/").toString();
        // Remove trailing slash
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        client = TrinityClient.builder().endpoint(baseUrl).timeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("Should build client with valid endpoint")
    void shouldBuildClientWithValidEndpoint() {
        // Act
        TrinityClient testClient =
                TrinityClient.builder()
                        .endpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .build();

        // Assert
        assertNotNull(testClient);
        assertEquals(TrainingTestConstants.TEST_TRINITY_ENDPOINT, testClient.getEndpoint());
    }

    @Test
    @DisplayName("Should throw exception when endpoint is null")
    void shouldThrowExceptionWhenEndpointIsNull() {
        assertThrows(IllegalArgumentException.class, () -> TrinityClient.builder().build());
    }

    @Test
    @DisplayName("Should throw exception when endpoint is empty")
    void shouldThrowExceptionWhenEndpointIsEmpty() {
        assertThrows(
                IllegalArgumentException.class, () -> TrinityClient.builder().endpoint("").build());
    }

    @Test
    @DisplayName("Should send feedback request successfully")
    void shouldSendFeedbackRequestSuccessfully() throws InterruptedException {
        // Arrange
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"status\": \"success\", \"message\": \"OK\"}")
                        .setHeader("Content-Type", "application/json"));

        FeedbackRequest request =
                FeedbackRequest.builder()
                        .taskId(TrainingTestConstants.TEST_TASK_ID)
                        .runId(TrainingTestConstants.TEST_RUN_ID)
                        .msgIds(
                                Arrays.asList(
                                        TrainingTestConstants.TEST_MSG_ID_1,
                                        TrainingTestConstants.TEST_MSG_ID_2))
                        .reward(0.8)
                        .build();

        // Act & Assert
        StepVerifier.create(client.feedback(request)).verifyComplete();

        // Verify request
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("/feedback", recordedRequest.getPath());
        assertEquals("POST", recordedRequest.getMethod());
        String body = recordedRequest.getBody().readUtf8();
        assertTrue(body.contains("\"task_id\":\"" + TrainingTestConstants.TEST_TASK_ID + "\""));
        assertTrue(body.contains("\"run_id\":\"" + TrainingTestConstants.TEST_RUN_ID + "\""));
        assertTrue(body.contains("\"reward\":0.8"));
    }

    @Test
    @DisplayName("Should handle feedback API error")
    void shouldHandleFeedbackApiError() {
        // Arrange
        mockServer.enqueue(
                new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        FeedbackRequest request =
                FeedbackRequest.builder()
                        .taskId(TrainingTestConstants.TEST_TASK_ID)
                        .runId(TrainingTestConstants.TEST_RUN_ID)
                        .msgIds(Arrays.asList(TrainingTestConstants.TEST_MSG_ID_1))
                        .reward(0.5)
                        .build();

        // Act & Assert
        StepVerifier.create(client.feedback(request)).expectError(RuntimeException.class).verify();
    }

    @Test
    @DisplayName("Should send commit request successfully")
    void shouldSendCommitRequestSuccessfully() throws InterruptedException {
        // Arrange
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"status\": \"success\", \"message\": \"OK\"}")
                        .setHeader("Content-Type", "application/json"));

        CommitRequest request =
                CommitRequest.builder()
                        .taskId(TrainingTestConstants.TEST_TASK_ID)
                        .runId(TrainingTestConstants.TEST_RUN_ID)
                        .timeThreshold(300000L)
                        .build();

        // Act & Assert
        StepVerifier.create(client.commit(request)).verifyComplete();

        // Verify request
        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertEquals("/commit", recordedRequest.getPath());
        assertEquals("POST", recordedRequest.getMethod());
    }

    @Test
    @DisplayName("Should handle commit API error")
    void shouldHandleCommitApiError() {
        // Arrange
        mockServer.enqueue(new MockResponse().setResponseCode(400).setBody("Bad Request"));

        CommitRequest request = CommitRequest.builder().build();

        // Act & Assert
        StepVerifier.create(client.commit(request)).expectError(RuntimeException.class).verify();
    }

    @Test
    @DisplayName("Should use custom timeout")
    void shouldUseCustomTimeout() {
        // Act
        TrinityClient testClient =
                TrinityClient.builder()
                        .endpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                        .timeout(Duration.ofSeconds(60))
                        .build();

        // Assert
        assertNotNull(testClient);
    }

    @Test
    @DisplayName("Should serialize feedback request with correct JSON field names")
    void shouldSerializeFeedbackRequestWithCorrectJsonFieldNames() throws InterruptedException {
        // Arrange
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"status\": \"success\", \"message\": \"OK\"}")
                        .setHeader("Content-Type", "application/json"));

        FeedbackRequest request =
                FeedbackRequest.builder()
                        .taskId("task-123")
                        .runId("0")
                        .msgIds(Arrays.asList("msg-1", "msg-2"))
                        .reward(0.9)
                        .build();

        // Act
        client.feedback(request).block();

        // Assert
        RecordedRequest recordedRequest = mockServer.takeRequest();
        String body = recordedRequest.getBody().readUtf8();
        assertTrue(body.contains("\"msg_ids\""));
        assertTrue(body.contains("\"task_id\""));
        assertTrue(body.contains("\"run_id\""));
    }
}
