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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

class LearnedContextWindowsTest {

    @Test
    void parsesWindowsFromCommonProviderMessages() {
        assertEquals(
                128_000,
                parse(
                        "This model's maximum context length is 128000 tokens. However, your"
                                + " messages resulted in 131000 tokens."));
        assertEquals(
                1_048_576,
                parse(
                        "The input token count (1048577) exceeds the maximum number of tokens"
                                + " allowed (1048576)."));
        assertEquals(200_000, parse("prompt is too long: 210000 tokens > 200000 maximum"));
        assertEquals(
                200_000,
                parse(
                        "input length and max_tokens exceed context limit: 210000 + 8192 >"
                                + " 200000"));
        assertEquals(1_000_000, parse("This model's maximum context length is 1,000,000 tokens."));
        assertEquals(131_072, parse("Range of input length should be [1, 131072]"));
        assertEquals(131_072, parse("Input length 200000 exceeds max input length 131072"));
        assertEquals(262_144, parse("context window: 262144 tokens"));
    }

    @Test
    void returnsZeroWhenTheMessageStatesNoWindow() {
        assertEquals(0, parse(null));
        assertEquals(0, parse("   "));
        assertEquals(
                0,
                parse(
                        "Your input exceeds the context window of this model. Please adjust your"
                                + " input and try again."));
        assertEquals(0, parse("context_length_exceeded"));
    }

    @Test
    void ignoresImplausiblySmallNumbers() {
        assertEquals(0, parse("maximum context length is 12 tokens"));
        assertEquals(0, parse("max_tokens 8192"));
    }

    @Test
    void learnsOnlyWhileTheModelReportsNoWindow() {
        LearnedContextWindows windows = new LearnedContextWindows();
        Model unknown = model("deepseek-chat", 0);

        windows.recordOverflow(
                unknown, new IllegalStateException("maximum context length is 65536 tokens"));

        assertEquals(65_536, windows.effectiveWindow(unknown));

        Model declared = model("gpt-4o", 128_000);
        windows.recordOverflow(
                declared, new IllegalStateException("maximum context length is 200000 tokens"));

        assertEquals(128_000, windows.effectiveWindow(declared));
        assertEquals(0, windows.remembered("gpt-4o"));
    }

    @Test
    void keepsTheSmallerOfRepeatedValues() {
        LearnedContextWindows windows = new LearnedContextWindows();
        Model model = model("custom-gateway", 0);

        windows.recordOverflow(
                model, new IllegalStateException("maximum context length is 128000 tokens"));
        assertEquals(128_000, windows.effectiveWindow(model));

        windows.recordOverflow(
                model, new IllegalStateException("maximum context length is 100000 tokens"));
        assertEquals(100_000, windows.effectiveWindow(model));

        windows.recordOverflow(
                model, new IllegalStateException("maximum context length is 200000 tokens"));
        assertEquals(100_000, windows.effectiveWindow(model));
    }

    @Test
    void evictsTheLeastRecentlyUsedModelBeyondTheBound() {
        LearnedContextWindows windows = new LearnedContextWindows();
        for (int i = 0; i <= LearnedContextWindows.MAX_ENTRIES; i++) {
            windows.recordOverflow(
                    model("model-" + i, 0),
                    new IllegalStateException("maximum context length is 100000 tokens"));
        }

        assertEquals(0, windows.remembered("model-0"));
        assertEquals(100_000, windows.remembered("model-" + LearnedContextWindows.MAX_ENTRIES));
    }

    @Test
    void readsTheMessageFromTheCauseChain() {
        LearnedContextWindows windows = new LearnedContextWindows();
        Model model = model("wrapped-model", 0);
        RuntimeException wrapped =
                new RuntimeException(
                        "provider call failed",
                        new IllegalStateException("prompt is too long: 300000 tokens > 200000"));

        windows.recordOverflow(model, wrapped);

        assertEquals(200_000, windows.effectiveWindow(model));
    }

    @Test
    void toleratesMissingInputs() {
        LearnedContextWindows windows = new LearnedContextWindows();

        windows.recordOverflow(
                null, new IllegalStateException("maximum context length is 100 tokens"));
        windows.recordOverflow(model("model", 0), null);

        assertEquals(0, windows.effectiveWindow(null));
        assertEquals(0, windows.effectiveWindow(model("model", 0)));
    }

    private static int parse(String message) {
        return LearnedContextWindows.parseWindow(message);
    }

    private static Model model(String name, int declaredWindow) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn(name);
        when(model.getContextWindowSize()).thenReturn(declaredWindow);
        return model;
    }
}
