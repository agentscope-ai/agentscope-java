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
package io.agentscope.core.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark a method as a tool that can be invoked by AI agents.
 *
 * <p>Methods annotated with {@code @Tool} are automatically registered with the toolkit and made
 * available to agents for execution. The toolkit uses reflection to discover tool methods and
 * generate appropriate JSON schemas for LLM consumption.
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * public class WeatherTools {
 *     @Tool(name = "get_weather", description = "Get current weather for a city")
 *     public String getWeather(
 *         @ToolParam(name = "city", description = "City name") String city,
 *         @ToolParam(name = "unit", description = "Temperature unit") String unit) {
 *         // Implementation
 *         return "Weather data...";
 *     }
 * }
 * }</pre>
 *
 * <p><b>Requirements:</b>
 * <ul>
 *   <li>All parameters must be annotated with {@link ToolParam} (except {@link ToolEmitter})</li>
 *   <li>Return type must be String, Mono&lt;String&gt;, or other reactive types</li>
 *   <li>Tool names should follow snake_case convention for LLM compatibility</li>
 *   <li>Descriptions should clearly explain what the tool does and when to use it</li>
 * </ul>
 *
 * @see ToolParam
 * @see Toolkit
 * @see ToolEmitter
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tool {

    /**
     * The name of the tool.
     *
     * <p>If not provided, the method name will be used. Tool names should follow snake_case
     * convention (e.g., "get_weather", "send_email") for compatibility with various LLM providers.
     *
     * @return The tool name, or empty string to use method name
     */
    String name() default "";

    /**
     * The description of the tool that explains its purpose and usage.
     *
     * <p>This description is sent to the LLM to help it decide when to invoke the tool. It should
     * clearly explain:
     * <ul>
     *   <li>What the tool does</li>
     *   <li>When it should be used</li>
     *   <li>What kind of results it returns</li>
     * </ul>
     *
     * <p>If not provided, a generic description based on the method name will be generated.
     *
     * @return The tool description, or empty string to auto-generate
     */
    String description() default "";

    /**
     * Whether to enable strict schema mode for this tool.
     *
     * <p>When enabled, compatible model providers can enforce stronger adherence to the declared
     * JSON schema for tool arguments.
     *
     * @return true to enable strict mode for this tool
     */
    boolean strict() default false;

    /**
     * Whether the tool only reads data without observable side effects.
     *
     * <p>Read-only tools are auto-allowed under {@code PermissionMode.EXPLORE} and
     * {@code ACCEPT_EDITS}, mirroring the {@code ToolBase.isReadOnly()} contract.
     *
     * @return true if this tool performs no mutation
     */
    boolean readOnly() default false;

    /**
     * Whether the tool is safe to invoke concurrently with itself.
     *
     * <p>When false, the framework serialises invocations of this tool inside a parallel batch.
     * Defaults to {@code true} to match the typical pure-function shape of {@code @Tool} methods.
     *
     * @return true if multiple invocations may run in parallel without coordination
     */
    boolean concurrencySafe() default true;

    /**
     * Whether the tool is executed outside the framework.
     *
     * <p>External tools never run their {@code callAsync} body. Instead the framework throws a
     * {@code ToolSuspendException} so the agent loop can surface the call to the caller via a
     * {@code TOOL_SUSPENDED} message.
     *
     * @return true if this tool should be surfaced as a suspended call
     */
    boolean externalTool() default false;

    /**
     * Whether the tool requires the agent state to be injected at call time.
     *
     * <p>When true, the method signature must declare exactly one
     * {@code io.agentscope.core.state.AgentState} parameter; the framework excludes that
     * parameter from the JSON schema and binds the live state at invocation.
     *
     * @return true if the tool method consumes the agent state
     */
    boolean stateInjected() default false;

    /**
     * Sensitive filenames that must require explicit permission for this tool.
     *
     * <p>An empty array sticks with the default list maintained by {@code ToolBase}.
     *
     * @return additional dangerous filenames; empty means use defaults
     */
    String[] dangerousFiles() default {};

    /**
     * Sensitive directory names whose presence in any path segment marks the path dangerous.
     *
     * <p>An empty array sticks with the default list maintained by {@code ToolBase}.
     *
     * @return additional dangerous directory names; empty means use defaults
     */
    String[] dangerousDirectories() default {};

    /**
     * Custom result converter for this tool.
     *
     * <p>Converters transform tool method return values into {@link io.agentscope.core.message.ToolResultBlock}
     * instances suitable for LLM consumption. Use custom converters to:
     * <ul>
     *   <li>Filter sensitive data from results</li>
     *   <li>Format output in specific ways</li>
     *   <li>Add metadata to results</li>
     *   <li>Compress or summarize large outputs</li>
     * </ul>
     *
     * <p><b>Usage Example:</b>
     * <pre>{@code
     * @Tool(
     *     name = "get_data",
     *     converter = CustomJsonConverter.class
     * )
     * public MyData getData(String id) {
     *     return dataService.findById(id);
     * }
     * }</pre>
     *
     * <p>If not specified, the default converter ({@link DefaultToolResultConverter}) is used,
     * which provides JSON serialization with schema information.
     *
     * <p><b>Note:</b> If you need complex processing with multiple steps, implement your own
     * converter that combines the necessary logic.
     *
     * @return Converter class
     * @see ToolResultConverter
     * @see DefaultToolResultConverter
     */
    Class<? extends ToolResultConverter> converter() default DefaultToolResultConverter.class;
}
