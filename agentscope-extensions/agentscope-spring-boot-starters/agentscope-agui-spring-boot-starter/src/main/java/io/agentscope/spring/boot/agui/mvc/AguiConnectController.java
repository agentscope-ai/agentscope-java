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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * MVC REST controller for the AG-UI {@code /connect} hydrate route.
 *
 * <p>Registered as a conditional bean only when the presentation snapshot store is enabled, so the
 * MVC {@code POST {path-prefix}/connect} route mirrors the WebFlux {@code RouterFunction} gating:
 * absent (404) when the store is off, present when on. When off, hydrate has nothing to read and
 * clients should not rely on the endpoint.
 *
 * <p>Delegates to {@link AguiMvcController#handleConnect} for the read-only SSE stream.
 */
@RestController
public class AguiConnectController {

    private final AguiMvcController aguiMvcController;
    private final AguiRequestBodyParser requestBodyParser;
    private final AguiEventEncoder encoder = new AguiEventEncoder();

    /**
     * Creates a new AguiConnectController.
     *
     * @param aguiMvcController The AG-UI MVC controller
     * @param requestBodyParser The parser used to decode request bodies
     */
    public AguiConnectController(
            AguiMvcController aguiMvcController, AguiRequestBodyParser requestBodyParser) {
        this.aguiMvcController = aguiMvcController;
        this.requestBodyParser = requestBodyParser;
    }

    /**
     * Handle an AG-UI {@code /connect} hydrate request.
     *
     * @param body The raw run agent input JSON (threadId / runId identify the snapshot)
     * @param request The native servlet request (may carry headers for the runtime context)
     * @return An SseEmitter for the read-only hydrate SSE stream
     */
    @PostMapping(
            value = "${agentscope.agui.path-prefix:/agui}/connect",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@RequestBody String body, HttpServletRequest request) {
        RunAgentInput input = requestBodyParser.parse(body);
        return aguiMvcController.handleConnect(input, request);
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
