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
package io.agentscope.core.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link FinalAnswerDecision} value type.
 *
 * @author shanhongyu
 */
@DisplayName("FinalAnswerDecision")
class FinalAnswerDecisionTest {

    private static Msg msg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    @Test
    @DisplayName("accept() is a shared plain accept without replacement")
    void plainAcceptIsSharedSingleton() {
        FinalAnswerDecision first = FinalAnswerDecision.accept();
        FinalAnswerDecision second = FinalAnswerDecision.accept();
        assertSame(first, second);
        assertEquals(FinalAnswerDecision.Kind.ACCEPT, first.kind());
        assertNull(first.replacement());
        assertTrue(first.retryMessages().isEmpty());
    }

    @Test
    @DisplayName("accept(replacement) carries the replacement")
    void acceptWithReplacementCarriesIt() {
        Msg replacement = msg("rewritten");
        FinalAnswerDecision decision = FinalAnswerDecision.accept(replacement);
        assertEquals(FinalAnswerDecision.Kind.ACCEPT, decision.kind());
        assertSame(replacement, decision.replacement());
        assertTrue(decision.retryMessages().isEmpty());
    }

    @Test
    @DisplayName("retry(list) exposes an immutable copy of the injected messages")
    void retryListCopiesInput() {
        List<Msg> msgs = new ArrayList<>();
        msgs.add(msg("reminder"));
        FinalAnswerDecision decision = FinalAnswerDecision.retry(msgs);

        assertTrue(decision.isRetry());
        assertEquals(msgs, decision.retryMessages());
        assertNull(decision.replacement());
        assertThrows(
                UnsupportedOperationException.class,
                () -> decision.retryMessages().add(msg("extra")));
    }

    @Test
    @DisplayName("retry(msg) wraps the single message")
    void retrySingleMsgWrapsIt() {
        Msg reminder = msg("fix it");
        FinalAnswerDecision decision = FinalAnswerDecision.retry(reminder);
        assertTrue(decision.isRetry());
        assertEquals(List.of(reminder), decision.retryMessages());
    }

    @Test
    @DisplayName("factories reject null payloads")
    void factoriesRejectNull() {
        assertThrows(NullPointerException.class, () -> FinalAnswerDecision.accept(null));
        assertThrows(NullPointerException.class, () -> FinalAnswerDecision.retry((Msg) null));
        assertThrows(NullPointerException.class, () -> FinalAnswerDecision.retry((List<Msg>) null));
        assertThrows(NullPointerException.class, () -> FinalAnswerDecision.retry(List.of(null)));
    }

    @Test
    @DisplayName("retry with an empty list is legal — candidate alone re-enters reasoning")
    void retryWithEmptyListIsLegal() {
        FinalAnswerDecision decision = FinalAnswerDecision.retry(List.of());
        assertTrue(decision.isRetry());
        assertTrue(decision.retryMessages().isEmpty());
    }
}
