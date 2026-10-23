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
package io.agentscope.core.e2e.providers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to declare the capabilities of a ModelProvider.
 *
 * <p>This annotation is used on ModelProvider implementations to declare what capabilities the
 * model supports. The ProviderFactory uses this information to dynamically filter providers based
 * on required capabilities.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * @ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING, ModelCapability.IMAGE})
 * public class MyVisionProvider extends BaseModelProvider {
 *     // ...
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ModelCapabilities {

    /**
     * The capabilities supported by this provider.
     *
     * @return array of supported capabilities
     */
    ModelCapability[] value();
}
