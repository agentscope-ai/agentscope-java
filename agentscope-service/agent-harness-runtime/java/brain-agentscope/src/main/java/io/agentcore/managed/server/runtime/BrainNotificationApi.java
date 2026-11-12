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
package io.agentcore.managed.server.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.ExecutionAccess.Candidate;
import io.agentcore.managed.harness.runtime.ProtocolSchemas;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated hints only; response does not acknowledge work or grant a lease. */
@RestController
public final class BrainNotificationApi {
    private final SessionServiceBrainWorker worker;
    private final ObjectMapper mapper;
    private final ProtocolSchemas schemas = new ProtocolSchemas();

    public BrainNotificationApi(SessionServiceBrainWorker worker, ObjectMapper mapper) {
        this.worker = worker;
        this.mapper = mapper;
    }

    @PostMapping("/api/internal/brain/notify")
    public ResponseEntity<Void> notify(@RequestBody Candidate candidate) {
        try {
            schemas.validate("runtime/Notification", mapper.valueToTree(candidate));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().build();
        }
        return worker.notify(candidate)
                ? ResponseEntity.accepted().build()
                : ResponseEntity.status(429).build();
    }
}
