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

import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.MemberScope;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigPart;
import java.util.stream.Stream;

/**
 * A victools JSON Schema {@link Module} that reads {@link ToolParam} annotations
 * to populate {@code "description"} and {@code "required"} in the generated schema.
 *
 * <p>By default, un-annotated fields are treated as required. Pass
 * {@link Option#PROPERTY_REQUIRED_FALSE_BY_DEFAULT} to invert this.
 *
 * @see ToolParam
 */
public class ToolSchemaModule implements Module {

    private final boolean requiredByDefault;

    /**
     * Creates a module with the given options.
     *
     * @param options pass {@link Option#PROPERTY_REQUIRED_FALSE_BY_DEFAULT}
     *                to treat un-annotated fields as optional
     */
    public ToolSchemaModule(Option... options) {
        this.requiredByDefault =
                Stream.of(options)
                        .noneMatch(option -> option == Option.PROPERTY_REQUIRED_FALSE_BY_DEFAULT);
    }

    @Override
    public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
        this.applyToConfigBuilder(builder.forFields());
    }

    /**
     * Registers description resolver and required check.
     */
    private void applyToConfigBuilder(SchemaGeneratorConfigPart<FieldScope> configPart) {
        configPart.withPropertyNameOverrideResolver(this::resolvePropertyName);
        configPart.withDescriptionResolver(this::resolveDescription);
        configPart.withRequiredCheck(this::checkRequired);
    }

    /**
     * Returns the {@code @ToolParam} name, or {@code null} if absent.
     *
     * @param member the field or getter being processed
     * @return the property name, or {@code null}
     */
    private String resolvePropertyName(FieldScope member) {
        ToolParam toolParam = member.getAnnotationConsideringFieldAndGetter(ToolParam.class);
        if (toolParam != null && hasText(toolParam.name())) {
            return toolParam.name();
        }
        return null;
    }

    /**
     * Returns the {@code @ToolParam} description, or {@code null} if absent.
     *
     * @param member the field or getter being processed
     * @return the description text, or {@code null}
     */
    private String resolveDescription(MemberScope<?, ?> member) {
        ToolParam toolParam = member.getAnnotationConsideringFieldAndGetter(ToolParam.class);
        if (toolParam != null && hasText(toolParam.description())) {
            return toolParam.description();
        }
        return null;
    }

    /**
     * Returns {@code @ToolParam.required()}, falling back to {@link #requiredByDefault}.
     *
     * @param member the field or getter being processed
     * @return {@code true} if the field should be required
     */
    private boolean checkRequired(MemberScope<?, ?> member) {
        ToolParam toolParam = member.getAnnotationConsideringFieldAndGetter(ToolParam.class);
        if (toolParam != null) {
            return toolParam.required();
        }
        return this.requiredByDefault;
    }

    /**
     * Returns {@code true} if the string is non-null and non-blank.
     */
    private static boolean hasText(String str) {
        return str != null && !str.isBlank();
    }

    /**
     * Options for customizing module behavior.
     */
    public enum Option {

        /**
         * Treat un-annotated fields as optional instead of required.
         */
        PROPERTY_REQUIRED_FALSE_BY_DEFAULT
    }
}
