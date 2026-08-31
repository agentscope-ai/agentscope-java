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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import io.agentscope.core.util.JsonException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class AguiRestControllerTest {

    @Test
    void shouldReturnBadRequestSseForParseErrors() {
        AguiRestController controller =
                new AguiRestController(null, "/agui", true, new AguiRequestBodyParser());

        ResponseEntity<String> response =
                controller.handleParseError(new JsonException("bad json"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Failed to parse request: bad json"));
        assertTrue(response.getBody().contains("data: "));
    }
}
