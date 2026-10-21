/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.portal.api;

import io.agentscope.builder.portal.session.PortalSessionCheckpoints;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/sessions/{session}/checkpoints")
public final class PortalSessionCheckpointsController {
    private final PortalSessionCheckpoints checkpoints;

    public PortalSessionCheckpointsController(PortalSessionCheckpoints checkpoints) {
        this.checkpoints = checkpoints;
    }

    @GetMapping
    public Mono<Map<String, Object>> list(
            @PathVariable String session,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) String through,
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader HttpHeaders headers,
            ServerHttpResponse response) {
        response.getHeaders().setCacheControl("no-store");
        return checkpoints.list(session, after, through, limit, headers);
    }

    @GetMapping("/{checkpoint}")
    public Mono<Map<String, Object>> metadata(
            @PathVariable String session,
            @PathVariable String checkpoint,
            @RequestHeader HttpHeaders headers,
            ServerHttpResponse response) {
        response.getHeaders().setCacheControl("no-store");
        return checkpoints.metadata(session, checkpoint, headers);
    }
}
