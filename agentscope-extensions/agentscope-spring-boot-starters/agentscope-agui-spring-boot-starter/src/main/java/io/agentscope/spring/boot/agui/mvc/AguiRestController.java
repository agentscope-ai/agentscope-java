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

import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.util.JsonException;
import io.agentscope.spring.boot.agui.common.AguiRequestBodyParser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST controller for AG-UI protocol endpoints.
 *
 * <p>This controller exposes the AG-UI run endpoints for Spring MVC applications.
 * It delegates the actual processing to {@link AguiMvcController}.
 *
 * <p><b>Why raw JSON + {@link AguiRequestBodyParser} instead of
 * {@code @RequestBody RunAgentInput}?</b>
 * <ul>
 *   <li>Spring Boot 4 uses Jackson 3 by default for {@code @RequestBody} binding.</li>
 *   <li>AG-UI {@code MessageContent} relies on Jackson 2 databind annotations
 *       ({@code @JsonDeserialize} / {@code @JsonSerialize} under
 *       {@code com.fasterxml.jackson.databind.annotation}).</li>
 *   <li>Those databind annotations moved to {@code tools.jackson.databind.annotation}
 *       in Jackson 3, so Jackson 3 ignores the existing ones and fails with
 *       {@code Type definition error: [simple type, class ...MessageContent]}.</li>
 *   <li>Reading the body as a raw JSON string and decoding it with AgentScope
 *       {@code JsonUtils} (Jackson 2) keeps the custom AG-UI deserializers working.</li>
 * </ul>
 */
@RestController
public class AguiRestController {

    private final AguiMvcController aguiMvcController;
    private final String pathPrefix;
    private final boolean enablePathRouting;

    /**
     * Creates a new AguiRestController.
     *
     * @param aguiMvcController The AG-UI MVC controller
     * @param pathPrefix The path prefix for endpoints
     * @param enablePathRouting Whether to enable path variable routing
     */
    public AguiRestController(
            AguiMvcController aguiMvcController, String pathPrefix, boolean enablePathRouting) {
        this.aguiMvcController = aguiMvcController;
        this.pathPrefix = pathPrefix;
        this.enablePathRouting = enablePathRouting;
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
     * <p>The body is bound as a raw JSON {@link String} (not {@link RunAgentInput}) so
     * Spring's Jackson 3 converter does not attempt to construct {@code MessageContent}.
     * See class-level Javadoc for the Spring Boot 4 / Jackson 3 rationale.
     *
     * @param body The raw JSON run-agent request body
     * @param agentIdHeader The agent ID from HTTP header (optional)
     * @param request The native servlet request
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
        return aguiMvcController.handle(parseBody(body), agentIdHeader, request);
    }

    /**
     * Handle an AG-UI run request with agent ID in the URL path.
     *
     * <p>The path variable takes highest priority for agent resolution.
     *
     * <p>The body is bound as a raw JSON {@link String} (not {@link RunAgentInput}) so
     * Spring's Jackson 3 converter does not attempt to construct {@code MessageContent}.
     * See class-level Javadoc for the Spring Boot 4 / Jackson 3 rationale.
     *
     * @param agentId The agent ID from path variable
     * @param body The raw JSON run-agent request body
     * @param agentIdHeader The agent ID from HTTP header (optional)
     * @param request The native servlet request
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
        return aguiMvcController.handleWithAgentId(
                parseBody(body), agentIdHeader, agentId, request);
    }

    /**
     * Decode the raw JSON body with AgentScope Jackson 2 ({@link AguiRequestBodyParser}).
     *
     * <p>Do not replace this with Spring's {@code @RequestBody RunAgentInput}: Boot 4's
     * Jackson 3 stack cannot bind {@code MessageContent} via the existing Jackson-2
     * {@code @JsonDeserialize} annotation.
     *
     * @param body raw JSON request body
     * @return parsed AG-UI run input
     */
    private static RunAgentInput parseBody(String body) {
        try {
            return AguiRequestBodyParser.parseRunAgentInput(body);
        } catch (JsonException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Failed to parse request: " + e.getMessage(), e);
        }
    }
}
