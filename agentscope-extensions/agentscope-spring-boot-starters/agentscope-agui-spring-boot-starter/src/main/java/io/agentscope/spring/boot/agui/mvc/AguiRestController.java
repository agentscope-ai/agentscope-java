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
package io.agentscope.spring.boot.agui.mvc;

import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import io.agentscope.core.util.JsonException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST controller for AG-UI protocol endpoints.
 *
 * <p>This controller exposes the AG-UI run endpoints for Spring MVC applications.
 * It delegates the actual processing to {@link AguiMvcController}.
 */
@RestController
public class AguiRestController {

    private final AguiMvcController aguiMvcController;
    private final AguiEventEncoder encoder = new AguiEventEncoder();
    private final AguiRequestBodyParser requestBodyParser;
    private final String pathPrefix;
    private final boolean enablePathRouting;

    /**
     * Creates a new AguiRestController.
     *
     * @param aguiMvcController The AG-UI MVC controller
     * @param pathPrefix The path prefix for endpoints
     * @param enablePathRouting Whether to enable path variable routing
     * @param requestBodyParser The parser used to decode request bodies
     */
    public AguiRestController(
            AguiMvcController aguiMvcController,
            String pathPrefix,
            boolean enablePathRouting,
            AguiRequestBodyParser requestBodyParser) {
        this.aguiMvcController = aguiMvcController;
        this.pathPrefix = pathPrefix;
        this.enablePathRouting = enablePathRouting;
        this.requestBodyParser = requestBodyParser;
    }

    /**
     * Handle an AG-UI run request.
     *
     * <p>Agent ID is resolved from (in priority order):
     * <ol>
     *   <li>HTTP header (configurable, default: X-Agent-Id)</li>
     *   <li>forwardedProps.agentId in request body</li>
     *   <li>config.defaultAgentId</li>
     *   <li>"default"</li>
     * </ol>
     *
     * @param body The raw run agent input JSON
     * @param agentIdHeader The agent ID from HTTP header (optional)
     * @return An SseEmitter for streaming AG-UI events
     */
    @PostMapping(
            value = "${agentscope.agui.path-prefix:/agui}/run",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(
            @RequestBody String body,
            @RequestHeader(
                            value = "${agentscope.agui.agent-id-header:X-Agent-Id}",
                            required = false)
                    String agentIdHeader,
            HttpServletRequest request) {
        RunAgentInput input = requestBodyParser.parse(body);
        return aguiMvcController.handle(input, agentIdHeader, request);
    }

    /**
     * Handle an AG-UI run request with agent ID in the URL path.
     *
     * <p>The path variable takes highest priority for agent resolution.
     *
     * @param agentId The agent ID from path variable
     * @param body The raw run agent input JSON
     * @param agentIdHeader The agent ID from HTTP header (optional)
     * @return An SseEmitter for streaming AG-UI events
     */
    @PostMapping(
            value = "${agentscope.agui.path-prefix:/agui}/run/{agentId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runWithAgentId(
            @PathVariable String agentId,
            @RequestBody String body,
            @RequestHeader(
                            value = "${agentscope.agui.agent-id-header:X-Agent-Id}",
                            required = false)
                    String agentIdHeader,
            HttpServletRequest request) {
        RunAgentInput input = requestBodyParser.parse(body);
        return aguiMvcController.handleWithAgentId(input, agentIdHeader, agentId, request);
    }

    /**
     * Return HTTP 400 for AG-UI request body parsing failures.
     *
     * @param error the JSON parse failure
     * @return an SSE-compatible bad request response
     */
    @ExceptionHandler(JsonException.class)
    public ResponseEntity<String> handleParseError(JsonException error) {
        String errorEvent =
                encoder.encodeToJson(
                                new AguiEvent.Raw(
                                        "unknown",
                                        "unknown",
                                        Map.of(
                                                "error",
                                                "Failed to parse request: " + error.getMessage())))
                        .trim();
        String finishEvent =
                encoder.encodeToJson(new AguiEvent.RunFinished("unknown", "unknown")).trim();
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body("data: " + errorEvent + "\n\n" + "data: " + finishEvent + "\n\n");
    }
}
