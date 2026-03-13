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
package io.agentscope.core.live.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DashScopeModality Tests")
class DashScopeModalityTest {

    @Test
    @DisplayName("Should return text-only modality list")
    void shouldReturnTextOnlyModalityList() {
        List<String> result = DashScopeModality.TEXT.toApiList();

        assertEquals(List.of("text"), result);
    }

    @Test
    @DisplayName("Should return text and audio modality list")
    void shouldReturnTextAndAudioModalityList() {
        List<String> result = DashScopeModality.TEXT_AND_AUDIO.toApiList();

        assertEquals(List.of("text", "audio"), result);
    }
}
