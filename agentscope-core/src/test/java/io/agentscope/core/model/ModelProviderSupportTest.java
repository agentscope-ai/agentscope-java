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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModelProviderSupportTest {

    @Test
    void firstNonBlankTrimsAndSkipsEmptyValues() {
        assertEquals("hello", ModelProviderSupport.firstNonBlank(" ", null, " hello "));
        assertNull(ModelProviderSupport.firstNonBlank(null, "   "));
    }

    @Test
    void typedOptionsAreConvertedAndValidated() {
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .option("count", 7L)
                        .option("enabled", true)
                        .option("name", "  Ada  ")
                        .component(CharSequence.class, new StringBuilder("x"))
                        .build();

        assertEquals(7, ModelProviderSupport.intOption(context, "count"));
        assertEquals(true, ModelProviderSupport.booleanOption(context, "enabled"));
        assertEquals("Ada", ModelProviderSupport.stringOption(context, "name"));
        assertSame(
                context.component(CharSequence.class),
                ModelProviderSupport.findAssignableComponent(context, CharSequence.class));
    }

    @Test
    void typedOptionsRejectWrongTypes() {
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .option("count", "seven")
                        .option("enabled", "yes")
                        .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelProviderSupport.intOption(context, "count"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelProviderSupport.booleanOption(context, "enabled"));
    }
}
