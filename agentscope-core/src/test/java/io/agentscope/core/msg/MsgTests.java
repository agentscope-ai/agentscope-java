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
package io.agentscope.core.msg;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MsgTests {

    @Test
    void testConstructor() {
        Msg msg1 =
                Msg.builder()
                        .name("test")
                        .role(MsgRole.SYSTEM)
                        .content(new ContentBlock())
                        .timestamp(String.valueOf(System.currentTimeMillis()))
                        .metadata(Map.of("key", "value"))
                        .build();
        Assertions.assertNotNull(msg1);

        Msg msg2 =
                Msg.builder()
                        .name("test")
                        .role(MsgRole.SYSTEM)
                        .timestamp(String.valueOf(System.currentTimeMillis()))
                        .build();
        Assertions.assertNotNull(msg2);
    }
}
