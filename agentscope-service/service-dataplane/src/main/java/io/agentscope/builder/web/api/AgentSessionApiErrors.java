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
package io.agentscope.builder.web.api;

import io.agentscope.core.session.SessionLogException;
import java.util.Map;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Public errors for explicit inspection, reconciliation and idle-session control operations. */
@RestControllerAdvice(
        assignableTypes = {
            AgentSessionEventsController.class,
            AgentSessionTurnsController.class,
            AgentSessionTraceController.class,
            AgentSessionArtifactsController.class,
            AgentSessionFilesController.class,
            AgentSessionInputsController.class,
            AgentSessionChildrenController.class,
            AgentSessionCheckpointsController.class,
            AgentSessionWebhooksController.class,
            AgentSessionBudgetController.class
        })
public class AgentSessionApiErrors {
    @ExceptionHandler(DataBufferLimitException.class)
    public ResponseEntity<Map<String, String>> large(DataBufferLimitException error) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "file_too_large"));
    }

    @ExceptionHandler(SessionLogException.class)
    public ResponseEntity<Map<String, String>> history(SessionLogException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        Map.of(
                                "error",
                                "session_history_conflict",
                                "message",
                                String.valueOf(error.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest()
                .body(
                        Map.of(
                                "error",
                                "invalid_session_request",
                                "message",
                                String.valueOf(error.getMessage())));
    }
}
