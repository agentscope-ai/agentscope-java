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
 * Annotation to describe parameters of a tool method.
 *
 * <p>This annotation is required for all parameters of methods annotated with {@link Tool} (except
 * {@link ToolEmitter} which is auto-injected). It provides metadata for generating JSON schemas
 * that describe the tool's parameters to LLMs.
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * @Tool(name = "calculate_area", description = "Calculate rectangle area")
 * public double calculateArea(
 *     @ToolParam(name = "width", description = "Width in meters", required = true)
 *     double width,
 *     @ToolParam(name = "height", description = "Height in meters", required = true)
 *     double height,
 *     @ToolParam(name = "unit", description = "Unit of measurement", required = false)
 *     String unit
 * ) {
 *     // Implementation
 * }
 * }</pre>
 *
 * <p><b>Important Notes:</b>
 * <ul>
 *   <li>The {@code name} attribute is <b>required</b> because Java does not preserve parameter
 *       names at runtime by default</li>
 *   <li>Parameter names should follow snake_case convention for LLM compatibility</li>
 *   <li>Descriptions help the LLM understand what values to provide</li>
 *   <li>{@link ToolEmitter} parameters do not need this annotation (they are framework-injected)</li>
 * </ul>
 *
 * @see Tool
 * @see ToolEmitter
 */
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolParam {

    /**
     * The name of the tool parameter.
     *
     * <p><b>This attribute is required</b> because Java does not preserve parameter names at
     * runtime by default (unless compiled with {@code -parameters} flag, which is not reliable).
     * The toolkit uses this name to map LLM-provided arguments to method parameters.
     *
     * <p>Names should follow snake_case convention (e.g., "file_path", "max_results") for
     * compatibility with various LLM providers.
     *
     * @return The parameter name as it should appear in the tool schema
     */
    String name();

    /**
     * Whether this parameter is required.
     *
     * <p>Required parameters must be provided by the LLM when invoking the tool. Optional
     * parameters can be omitted, and the method will receive null (for objects) or default values
     * (for primitives).
     *
     * @return true if required (default), false if optional
     */
    boolean required() default true;

    /**
     * The description of this parameter.
     *
     * <p>This description is sent to the LLM as part of the tool schema to help it understand:
     * <ul>
     *   <li>What this parameter represents</li>
     *   <li>What format or values are expected</li>
     *   <li>Any constraints or validation rules</li>
     * </ul>
     *
     * <p>Good descriptions improve the LLM's ability to provide correct parameter values.
     *
     * @return The parameter description, or empty string if not provided
     */
    String description() default "";
}
