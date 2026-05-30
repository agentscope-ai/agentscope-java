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
package io.agentscope.core.llm.interfacesweb.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.ToolSchema;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ResponsesToolConverter Tests")
class ResponsesToolConverterTest {

    @Test
    @DisplayName("Should convert both flat and nested OpenAI function tool shapes")
    void shouldConvertFlatAndNestedFunctionTools() {
        ResponsesTool flat = new ResponsesTool();
        flat.setType("function");
        flat.setName("lookup");
        flat.setDescription("Lookup data");
        flat.setParameters(Map.of("type", "object"));
        flat.setStrict(true);

        ResponsesTool nested = new ResponsesTool();
        nested.setType("function");
        nested.setFunction(
                Map.of(
                        "name",
                        "search",
                        "description",
                        "Search data",
                        "parameters",
                        Map.of("type", "object"),
                        "strict",
                        false));

        List<ToolSchema> schemas = new ResponsesToolConverter().convert(List.of(flat, nested));

        assertEquals(2, schemas.size());
        assertEquals("lookup", schemas.get(0).getName());
        assertEquals(Boolean.TRUE, schemas.get(0).getStrict());
        assertEquals("search", schemas.get(1).getName());
        assertEquals(Boolean.FALSE, schemas.get(1).getStrict());
    }

    @Test
    @DisplayName("Should skip unsupported and invalid Responses tools")
    void shouldSkipUnsupportedAndInvalidResponsesTools() {
        ResponsesTool unsupported = new ResponsesTool();
        unsupported.setType("web_search_preview");

        ResponsesTool blankName = new ResponsesTool();
        blankName.setType("function");
        blankName.setName(" ");
        ResponsesTool missingName = new ResponsesTool();
        missingName.setType("function");

        List<ToolSchema> schemas =
                new ResponsesToolConverter()
                        .convert(Arrays.asList(null, unsupported, blankName, missingName));

        assertTrue(schemas.isEmpty());
        assertTrue(new ResponsesToolConverter().convert(null).isEmpty());
        assertTrue(new ResponsesToolConverter().convert(List.of()).isEmpty());
    }

    @Test
    @DisplayName("Should apply defaults for minimal Responses tools")
    void shouldApplyDefaultsForMinimalResponsesTools() {
        ResponsesTool minimal = new ResponsesTool();
        minimal.setType("function");
        minimal.setName("lookup");
        ResponsesTool noType = new ResponsesTool();
        noType.setType(null);
        noType.setName("no_type");

        ResponsesTool nested = new ResponsesTool();
        nested.setType("function");
        nested.setFunction(Map.of("name", "search", "parameters", "ignored", "strict", "yes"));

        List<ToolSchema> schemas =
                new ResponsesToolConverter().convert(List.of(minimal, noType, nested));

        assertEquals(3, schemas.size());
        assertEquals("", schemas.get(0).getDescription());
        assertEquals(Map.of("type", "object"), schemas.get(0).getParameters());
        assertEquals("no_type", schemas.get(1).getName());
        assertEquals("search", schemas.get(2).getName());
        assertEquals(Map.of("type", "object"), schemas.get(2).getParameters());
    }
}
