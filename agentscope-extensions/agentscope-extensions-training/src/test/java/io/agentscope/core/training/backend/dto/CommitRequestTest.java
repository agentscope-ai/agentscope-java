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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CommitRequest Unit Tests")
class CommitRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should build commit request with all fields")
    void shouldBuildCommitRequestWithAllFields() {
        // Act
        CommitRequest request =
                CommitRequest.builder()
                        .taskId(TrainingTestConstants.TEST_TASK_ID)
                        .runId(TrainingTestConstants.TEST_RUN_ID)
                        .timeThreshold(300000L)
                        .build();

        // Assert
        assertEquals(TrainingTestConstants.TEST_TASK_ID, request.getTaskId());
        assertEquals(TrainingTestConstants.TEST_RUN_ID, request.getRunId());
        assertEquals(300000L, request.getTimeThreshold());
    }

    @Test
    @DisplayName("Should serialize to JSON with snake_case field names")
    void shouldSerializeToJsonWithSnakeCaseFieldNames() throws JsonProcessingException {
        // Arrange
        CommitRequest request =
                CommitRequest.builder().taskId("task-123").runId("0").timeThreshold(60000L).build();

        // Act
        String json = objectMapper.writeValueAsString(request);

        // Assert
        assertTrue(json.contains("\"task_id\":\"task-123\""));
        assertTrue(json.contains("\"run_id\":\"0\""));
        assertTrue(json.contains("\"time_threshold\":60000"));
    }

    @Test
    @DisplayName("Should handle null values")
    void shouldHandleNullValues() {
        // Act
        CommitRequest request = CommitRequest.builder().build();

        // Assert
        assertNull(request.getTaskId());
        assertNull(request.getRunId());
        assertNull(request.getTimeThreshold());
    }
}
