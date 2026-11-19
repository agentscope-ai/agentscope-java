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
package io.agentscope.harness.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextTokenEstimatorTest {

    @Test
    void latinTextKeepsTwoAndAHalfCharsPerToken() {
        assertEquals(16, estimate("abcdefghij").tokens());
    }

    @Test
    void cjkIdeographsCarryDoubleWeight() {
        // 6 CJK chars weigh 12, ceil(12 / 2.5) = 5, plus 12 message overhead.
        assertEquals(17, estimate("汉字估算测试").tokens());
    }

    @Test
    void mixedTextWeightsEachScriptSeparately() {
        // 3 Latin (3) + 2 CJK (4) = 7 weighted, ceil(7 / 2.5) = 3, plus 12.
        assertEquals(15, estimate("abc汉字").tokens());
    }

    @Test
    void cjkRangeBoundariesAreInclusive() {
        // U+4DFF stays single, U+4E00 and U+9FFF weigh double: 1 + 2 + 2 = 5 -> 2, plus 12.
        assertEquals(14, estimate("䷿一鿿").tokens());
    }

    @Test
    void methodNameRecordsCjkWeighting() {
        assertEquals("mixed-text-cjk2x-2.5chars-media-2048-v2", estimate("x").method());
    }

    private static ContextTokenEstimator.Estimate estimate(String text) {
        return ContextTokenEstimator.approximate()
                .estimate(
                        new ModelCallInput(
                                List.of(Msg.builder().role(MsgRole.USER).textContent(text).build()),
                                List.of(),
                                null,
                                null));
    }
}
