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

import io.agentscope.core.model.ToolSchema;
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
}
