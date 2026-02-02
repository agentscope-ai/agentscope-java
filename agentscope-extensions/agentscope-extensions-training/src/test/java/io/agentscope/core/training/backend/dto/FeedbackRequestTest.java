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

package io.agentscope.core.training.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.training.util.TrainingTestConstants;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FeedbackRequest Unit Tests")
class FeedbackRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should build feedback request with all fields")
    void shouldBuildFeedbackRequestWithAllFields() {
        // Arrange
        List<String> msgIds =
                Arrays.asList(
                        TrainingTestConstants.TEST_MSG_ID_1, TrainingTestConstants.TEST_MSG_ID_2);

        // Act
        FeedbackRequest request =
                FeedbackRequest.builder()
                        .taskId(TrainingTestConstants.TEST_TASK_ID)
                        .runId(TrainingTestConstants.TEST_RUN_ID)
                        .msgIds(msgIds)
                        .reward(0.85)
                        .build();

        // Assert
        assertEquals(TrainingTestConstants.TEST_TASK_ID, request.getTaskId());
        assertEquals(TrainingTestConstants.TEST_RUN_ID, request.getRunId());
        assertEquals(2, request.getMsgIds().size());
        assertEquals(0.85, request.getReward(), 0.001);
    }

    @Test
    @DisplayName("Should serialize to JSON with snake_case field names")
    void shouldSerializeToJsonWithSnakeCaseFieldNames() throws JsonProcessingException {
        // Arrange
        FeedbackRequest request =
                FeedbackRequest.builder()
                        .taskId("task-123")
                        .runId("0")
                        .msgIds(Arrays.asList("msg-1", "msg-2"))
                        .reward(0.9)
                        .build();

        // Act
        String json = objectMapper.writeValueAsString(request);

        // Assert
        assertTrue(json.contains("\"task_id\":\"task-123\""));
        assertTrue(json.contains("\"run_id\":\"0\""));
        assertTrue(json.contains("\"msg_ids\""));
        assertTrue(json.contains("\"reward\":0.9"));
    }

    @Test
    @DisplayName("Should handle null values")
    void shouldHandleNullValues() {
        // Act
        FeedbackRequest request = FeedbackRequest.builder().build();

        // Assert
        assertNull(request.getTaskId());
        assertNull(request.getRunId());
        assertNull(request.getMsgIds());
        assertNull(request.getReward());
    }
}
