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
package io.agentscope.examples.harness.remote;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP API for the remote-store Data Agent ({@code RemoteFilesystemSpec} / filesystem.md 模式一).
 *
 * <pre>{@code
 * curl -X POST http://localhost:8788/query \
 *   -H 'Content-Type: application/json' \
 *   -d '{"sessionId":"s1","userId":"alice","question":"How many artists are in the database?"}'
 * }</pre>
 */
@RestController
public class DataAgentController {

    private final DataAgentService agentService;

    public DataAgentController(DataAgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/")
    public String index() {
        return "Remote-store Data Agent (RemoteFilesystemSpec) — POST /query with"
                + " {\"sessionId\":\"...\",\"userId\":\"...\",\"question\":\"...\"}";
    }

    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@RequestBody QueryRequest request) {
        if (blank(request.sessionId())) {
            return ResponseEntity.badRequest().body(QueryResponse.error("sessionId is required"));
        }
        if (blank(request.userId())) {
            return ResponseEntity.badRequest().body(QueryResponse.error("userId is required"));
        }
        if (blank(request.question())) {
            return ResponseEntity.badRequest().body(QueryResponse.error("question is required"));
        }
        String answer =
                agentService.query(request.sessionId(), request.userId(), request.question());
        return ResponseEntity.ok(QueryResponse.ok(answer));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    record QueryRequest(String sessionId, String userId, String question) {}

    record QueryResponse(String answer, String error) {
        static QueryResponse ok(String answer) {
            return new QueryResponse(answer, null);
        }

        static QueryResponse error(String error) {
            return new QueryResponse(null, error);
        }
    }
}
