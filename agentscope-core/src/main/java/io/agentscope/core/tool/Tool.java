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
}
