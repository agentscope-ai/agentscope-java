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
package io.agentscope.durable;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ToolCall;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** A frozen, Harness-neutral tool definition. Targets contain references/configuration, not resolved credentials. */
public record ToolBinding(
        String name, String version, String executor, JsonNode schema, JsonNode target) {
    public ToolBinding {
        DurableJson.text(name, "tool name");
        DurableJson.text(version, "tool version");
        DurableJson.text(executor, "executor");
        schema = DurableJson.copy(schema);
        target = DurableJson.copy(target);
        if (!schema.isObject()
                || !name.equals(schema.path("name").asText())
                || !schema.path("parameters").isObject()
                || !target.isObject())
            throw new IllegalArgumentException(
                    "Tool binding requires a matching schema and object target");
        DurableJson.requirePortableNumbers(schema);
        DurableJson.requirePortableNumbers(target);
    }

    @Override
    public JsonNode schema() {
        return schema.deepCopy();
    }

    @Override
    public JsonNode target() {
        return target.deepCopy();
    }

    public String digest() {
        return DurableJson.hash(this);
    }

    public ToolCall call(String callId, JsonNode arguments) {
        return new ToolCall(callId, executor, name, arguments, digest());
    }

    public void verify(ExecutionRequest request) {
        var call = request.call();
        if (!name.equals(call.name())
                || !executor.equals(call.executor())
                || !digest().equals(call.binding()))
            throw new DurableException(
                    DurableException.Code.INCOMPATIBLE, "Frozen tool binding differs");
    }

    public ToolBinding route(String executor) {
        return new ToolBinding(name, version, executor, schema, target);
    }

    public static Map<String, ToolBinding> catalog(Collection<ToolBinding> bindings) {
        var result = new LinkedHashMap<String, ToolBinding>();
        for (var binding : bindings)
            if (result.putIfAbsent(binding.name(), binding) != null)
                throw new IllegalArgumentException("Duplicate tool binding: " + binding.name());
        return Map.copyOf(result);
    }

    public static Map<String, String> routes(Collection<ToolBinding> bindings) {
        var result = new LinkedHashMap<String, String>();
        catalog(bindings).forEach((name, binding) -> result.put(name, binding.executor()));
        return Map.copyOf(result);
    }
}
