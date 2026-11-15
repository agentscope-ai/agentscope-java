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
package io.agentscope.core.agent.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Background memory maintenance calls the model after a turn returns, so a test can still be
 * iterating {@link MockModel#getRecordedMessages()} when the next request is recorded.
 */
class MockModelRecordedMessagesTest {

    @Test
    void iterationSurvivesARequestRecordedMidway() {
        MockModel model = new MockModel("ack");
        Msg probe =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("probe").build())
                        .build();
        model.stream(List.of(probe), null, null).blockLast();

        Iterator<List<Msg>> inFlight = model.getRecordedMessages().iterator();
        inFlight.next();

        // The call a background maintenance turn makes while the test is still iterating.
        model.stream(List.of(probe), null, null).blockLast();

        assertDoesNotThrow(
                () -> {
                    while (inFlight.hasNext()) {
                        inFlight.next();
                    }
                },
                "recording a request invalidated an iterator a test was still draining");
    }
}
