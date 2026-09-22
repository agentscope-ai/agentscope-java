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

package io.agentscope.extensions.jev.middleware;

import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.extensions.jev.ChoiceAnswer;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JevSelectionSupportTest {

    @Test
    void topNameReturnsNullWhenNoneHasHighestProbability() {
        ChoiceAnswer answer =
                new ChoiceAnswer(
                        JevSelectionSupport.NONE_OPTION,
                        Map.of("search", 0.1, JevSelectionSupport.NONE_OPTION, 0.9),
                        0.9);

        assertNull(JevSelectionSupport.topName(answer));
    }
}
