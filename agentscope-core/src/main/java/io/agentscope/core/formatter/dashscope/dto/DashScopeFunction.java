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
package io.agentscope.core.formatter.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DashScope function 调用 DTO，对应 tool_calls 数组中的 function 对象。
 *
 * <p>DashScope API 中，tool call 的参数以 JSON 字符串形式传输。本类的两个字段
 * 经 DashScopeResponseParser 映射到 AgentScope 的 ToolUseBlock：
 *
 * <pre>{@code
 * DashScopeFunction                  ToolUseBlock
 * ───────────────────────────────────────────────
 * name        ───────────────────→  name          (工具名称)
 * arguments   ───────────────────→  content       (原始 JSON 字符串，非结构化)
 *              后续由 ModelUtils   →  input        (解析为 Map<String, Object>)
 * }</pre>
 *
 * <p>JSON 示例：
 * <pre>{@code
 * {
 *   "name": "get_weather",
 *   "arguments": "{\"location\": \"Beijing\"}"
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeFunction {

    /** The name of the function to call. */
    @JsonProperty("name")
    private String name;

    /** The arguments to pass to the function (JSON string). */
    @JsonProperty("arguments")
    private String arguments;

    public DashScopeFunction() {}

    public DashScopeFunction(String name, String arguments) {
        this.name = name;
        this.arguments = arguments;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public static DashScopeFunction of(String name, String arguments) {
        return new DashScopeFunction(name, arguments);
    }
}
