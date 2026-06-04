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
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentStateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsAreApplied() {
        AgentState s = AgentState.builder().build();
        assertNotNull(s.getSessionId());
        assertEquals(
                32, s.getSessionId().length(), "default session id should be 32-char uuid hex");
        assertNotNull(s.getReplyId());
        assertEquals(32, s.getReplyId().length());
        assertEquals("", s.getSummary());
        assertEquals(List.of(), s.getContext());
        assertEquals(0, s.getCurIter());
        assertEquals(PermissionMode.DEFAULT, s.getPermissionContext().getMode());
        assertEquals(100, s.getToolContext().getMaxCacheFiles());
        assertEquals(List.of(), s.getTasksContext().getTasks());
    }

    @Test
    void explicitFieldsWin() {
        Msg msg = Msg.builder().role(MsgRole.USER).textContent("hello").build();
        PermissionContextState pc =
                PermissionContextState.builder().mode(PermissionMode.BYPASS).build();
        ToolContextState tc = ToolContextState.builder().maxCacheFiles(5).build();
        TaskContextState tasks =
                new TaskContextState(
                        List.of(
                                Task.builder()
                                        .subject("s")
                                        .description("d")
                                        .id("t1")
                                        .createdAt("2026-01-01T00:00:00+00:00")
                                        .build()));

        AgentState s =
                AgentState.builder()
                        .sessionId("sess-1")
                        .replyId("reply-1")
                        .summary("rolling summary")
                        .curIter(7)
                        .context(List.of(msg))
                        .permissionContext(pc)
                        .toolContext(tc)
                        .tasksContext(tasks)
                        .build();

        assertEquals("sess-1", s.getSessionId());
        assertEquals("reply-1", s.getReplyId());
        assertEquals("rolling summary", s.getSummary());
        assertEquals(7, s.getCurIter());
        assertEquals(1, s.getContext().size());
        assertEquals(PermissionMode.BYPASS, s.getPermissionContext().getMode());
        assertEquals(5, s.getToolContext().getMaxCacheFiles());
        assertEquals(1, s.getTasksContext().getTasks().size());
    }

    @Test
    void contextGetterReturnsDefensiveCopy() {
        AgentState s = AgentState.builder().build();
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        s.getContext()
                                .add(Msg.builder().role(MsgRole.USER).textContent("x").build()));
    }

    @Test
    void mutableContextHandleAppendsInPlace() {
        AgentState s = AgentState.builder().build();
        s.contextMutable().add(Msg.builder().role(MsgRole.USER).textContent("first").build());
        s.contextMutable().add(Msg.builder().role(MsgRole.ASSISTANT).textContent("reply").build());
        assertEquals(2, s.getContext().size());
    }

    @Test
    void settersBehave() {
        AgentState s = AgentState.builder().build();
        s.setCurIter(3);
        assertEquals(3, s.getCurIter());
        s.setSummary("new");
        assertEquals("new", s.getSummary());
        s.setSummary(null);
        assertEquals("", s.getSummary());
        s.setReplyId("explicit");
        assertEquals("explicit", s.getReplyId());
        s.setReplyId(null);
        assertEquals(32, s.getReplyId().length());
    }

    @Test
    void addMessageBuilderHelper() {
        AgentState s =
                AgentState.builder()
                        .addMessage(Msg.builder().role(MsgRole.USER).textContent("a").build())
                        .addMessage(Msg.builder().role(MsgRole.ASSISTANT).textContent("b").build())
                        .build();
        assertEquals(2, s.getContext().size());
    }

    @Test
    void jsonRoundTripPreservesScalars() throws Exception {
        AgentState original =
                AgentState.builder()
                        .sessionId("sess-9")
                        .replyId("reply-9")
                        .summary("rolling")
                        .curIter(5)
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.EXPLORE)
                                        .build())
                        .build();
        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"session_id\":\"sess-9\""), () -> json);
        assertTrue(json.contains("\"reply_id\":\"reply-9\""), () -> json);
        assertTrue(json.contains("\"cur_iter\":5"), () -> json);
        assertTrue(json.contains("\"summary\":\"rolling\""), () -> json);
        AgentState decoded = mapper.readValue(json, AgentState.class);
        assertEquals(original.getSessionId(), decoded.getSessionId());
        assertEquals(original.getReplyId(), decoded.getReplyId());
        assertEquals(original.getCurIter(), decoded.getCurIter());
        assertEquals(original.getSummary(), decoded.getSummary());
        assertEquals(
                original.getPermissionContext().getMode(),
                decoded.getPermissionContext().getMode());
    }

    @Test
    void jsonOmittedFieldsFallBackToDefaults() throws Exception {
        AgentState decoded = mapper.readValue("{\"session_id\":\"only-id\"}", AgentState.class);
        assertEquals("only-id", decoded.getSessionId());
        assertEquals("", decoded.getSummary());
        assertEquals(0, decoded.getCurIter());
        assertNotNull(decoded.getReplyId());
    }
}
