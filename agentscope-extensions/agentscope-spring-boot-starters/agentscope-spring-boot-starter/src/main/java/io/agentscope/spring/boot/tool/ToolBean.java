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
package io.agentscope.spring.boot.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for beans whose {@link io.agentscope.core.tool.Tool}-annotated
 * methods should be automatically registered into the {@link io.agentscope.core.tool.Toolkit}.
 *
 * <p>When this annotation is present on a bean, the
 * {@link ToolAutoRegistrationBeanPostProcessor} will detect it and call
 * {@code toolkit.registerTool(bean)} for every {@code Toolkit} bean created in
 * the application context.
 *
 * <p>Example:
 * <pre>{@code
 * @ToolBean
 * public class MyTools {
 *     @Tool(description = "Search the web")
 *     public String search(String query) { ... }
 *
 *     @Tool(description = "Calculate an expression")
 *     public double calculate(String expression) { ... }
 * }
 * }</pre>
 *
 * <p>Multiple {@code @Tool} methods on the same class are all registered in a
 * single {@code registerTool} call.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolBean {}
