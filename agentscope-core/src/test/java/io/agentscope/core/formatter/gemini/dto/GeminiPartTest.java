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
package io.agentscope.core.formatter.gemini.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonUtils;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("GeminiPart Unit Tests")
class GeminiPartTest {

    @Test
    @DisplayName("Should set and get top level fields")
    void testTopLevelFields() {
        GeminiPart part = new GeminiPart();
        GeminiPart.GeminiFunctionCall functionCall =
                new GeminiPart.GeminiFunctionCall("call_1", "lookup", Map.of("k", "v"));
        GeminiPart.GeminiFunctionResponse functionResponse =
                new GeminiPart.GeminiFunctionResponse("resp_1", "lookup", Map.of("output", "ok"));
        GeminiPart.GeminiBlob blob = new GeminiPart.GeminiBlob("image/png", "ZmFrZQ==");
        GeminiPart.GeminiFileData fileData =
                new GeminiPart.GeminiFileData("text/plain", "gs://bucket/file.txt");

        part.setText("hello");
        part.setFunctionCall(functionCall);
        part.setFunctionResponse(functionResponse);
        part.setInlineData(blob);
        part.setFileData(fileData);
        part.setThought(true);
        part.setSignature("sig");
        part.setThoughtSignature("thought-sig");

        assertEquals("hello", part.getText());
        assertEquals(functionCall, part.getFunctionCall());
        assertEquals(functionResponse, part.getFunctionResponse());
        assertEquals(blob, part.getInlineData());
        assertEquals(fileData, part.getFileData());
        assertEquals(true, part.getThought());
        assertEquals("sig", part.getSignature());
        assertEquals("thought-sig", part.getThoughtSignature());
    }

    @Test
    @DisplayName("Should support function call constructors and setters")
    void testFunctionCallConstructors() {
        GeminiPart.GeminiFunctionCall fromTwoArgs =
                new GeminiPart.GeminiFunctionCall("get_weather", Map.of("city", "SF"));
        GeminiPart.GeminiFunctionCall fromThreeArgs =
                new GeminiPart.GeminiFunctionCall("call_2", "get_weather", Map.of("city", "LA"));

        fromTwoArgs.setId("call_3");
        fromTwoArgs.setName("weather");
        fromTwoArgs.setArgs(Map.of("city", "Tokyo"));

        assertEquals("call_3", fromTwoArgs.getId());
        assertEquals("weather", fromTwoArgs.getName());
        assertEquals(Map.of("city", "Tokyo"), fromTwoArgs.getArgs());
        assertEquals("call_2", fromThreeArgs.getId());
        assertEquals("get_weather", fromThreeArgs.getName());
        assertEquals(Map.of("city", "LA"), fromThreeArgs.getArgs());
    }

    @Test
    @DisplayName("Should support function response constructors and setters")
    void testFunctionResponseConstructors() {
        GeminiPart.GeminiFunctionResponse fromTwoArgs =
                new GeminiPart.GeminiFunctionResponse("lookup", Map.of("output", "Paris"));
        GeminiPart.GeminiFunctionResponse fromThreeArgs =
                new GeminiPart.GeminiFunctionResponse("id_1", "lookup", Map.of("output", "Berlin"));

        fromTwoArgs.setId("id_2");
        fromTwoArgs.setName("lookup_city");
        fromTwoArgs.setResponse(Map.of("output", "Rome"));

        assertEquals("id_2", fromTwoArgs.getId());
        assertEquals("lookup_city", fromTwoArgs.getName());
        assertEquals(Map.of("output", "Rome"), fromTwoArgs.getResponse());
        assertEquals("id_1", fromThreeArgs.getId());
        assertEquals("lookup", fromThreeArgs.getName());
        assertEquals(Map.of("output", "Berlin"), fromThreeArgs.getResponse());
    }

    @Test
    @DisplayName("Should support blob and file data constructors and setters")
    void testBlobAndFileData() {
        GeminiPart.GeminiBlob blob = new GeminiPart.GeminiBlob();
        blob.setMimeType("audio/mp3");
        blob.setData("YXVkaW8=");
        assertEquals("audio/mp3", blob.getMimeType());
        assertEquals("YXVkaW8=", blob.getData());

        GeminiPart.GeminiFileData fileData = new GeminiPart.GeminiFileData();
        fileData.setMimeType("application/pdf");
        fileData.setFileUri("https://example.com/a.pdf");
        assertEquals("application/pdf", fileData.getMimeType());
        assertEquals("https://example.com/a.pdf", fileData.getFileUri());
    }

    @Test
    @DisplayName("Should serialize functionCall without id field")
    void testFunctionCallSerializationIgnoresId() {
        GeminiPart part = new GeminiPart();
        part.setFunctionCall(
                new GeminiPart.GeminiFunctionCall(
                        "internal-id", "get_capital", Map.of("country", "Japan")));

        String json = JsonUtils.getJsonCodec().toJson(part);

        assertTrue(json.contains("\"functionCall\""));
        assertTrue(json.contains("\"name\":\"get_capital\""));
        assertTrue(json.contains("\"args\":{\"country\":\"Japan\"}"));
        assertTrue(!json.contains("internal-id"));
    }
}
