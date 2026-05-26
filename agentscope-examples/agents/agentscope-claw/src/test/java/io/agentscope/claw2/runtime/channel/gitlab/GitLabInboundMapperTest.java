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
package io.agentscope.claw2.runtime.channel.gitlab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.claw2.runtime.channel.InboundMessage;
import io.agentscope.claw2.runtime.channel.PeerKind;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitLabInboundMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsIssueNoteHook() throws Exception {
        String payload =
                "{\"user\":{\"id\":42,\"username\":\"alice\"},"
                    + "\"project\":{\"path_with_namespace\":\"acme/widgets\",\"namespace\":\"acme\"},\"object_attributes\":{"
                    + "  \"id\":999,  \"note\":\"@bot please review\", "
                    + " \"noteable_type\":\"Issue\",  \"system\":false},\"issue\":{\"iid\":7}}";
        JsonNode node = MAPPER.readTree(payload);
        GitLabInboundMapper mapper = new GitLabInboundMapper("gitlab-dev");
        Optional<InboundMessage> result = mapper.map(node);
        assertTrue(result.isPresent());
        InboundMessage in = result.get();
        assertEquals("gitlab-dev", in.channelId());
        assertEquals(PeerKind.THREAD, in.peer().kind());
        assertEquals("acme/widgets#7:Issue", in.peer().id());
        assertEquals("alice", in.senderId());
        assertEquals("acme", in.accountId());
        assertEquals("@bot please review", in.messages().get(0).getTextContent());

        assertEquals(999L, GitLabInboundMapper.extractNoteId(node).orElseThrow());
        assertEquals(42L, GitLabInboundMapper.extractAuthorId(node).orElseThrow());
    }

    @Test
    void mapsMergeRequestNoteHook() throws Exception {
        String payload =
                "{\"user\":{\"id\":11,\"username\":\"bob\"},"
                    + "\"project\":{\"path_with_namespace\":\"team/proj\",\"namespace\":\"team\"},\"object_attributes\":{"
                    + "  \"id\":1234,  \"note\":\"LGTM\",  \"noteable_type\":\"MergeRequest\", "
                    + " \"system\":false},\"merge_request\":{\"iid\":3}}";
        JsonNode node = MAPPER.readTree(payload);
        GitLabInboundMapper mapper = new GitLabInboundMapper("gitlab-dev");
        Optional<InboundMessage> result = mapper.map(node);
        assertTrue(result.isPresent());
        assertEquals("team/proj#3:MergeRequest", result.get().peer().id());
    }

    @Test
    void skipsSystemNotes() throws Exception {
        String payload =
                "{"
                        + "\"user\":{\"id\":11,\"username\":\"u\"},"
                        + "\"project\":{\"path_with_namespace\":\"a/b\"},"
                        + "\"object_attributes\":{\"id\":1,\"note\":\"added label\","
                        + "  \"noteable_type\":\"Issue\",\"system\":true},"
                        + "\"issue\":{\"iid\":1}"
                        + "}";
        JsonNode node = MAPPER.readTree(payload);
        GitLabInboundMapper mapper = new GitLabInboundMapper("gitlab-dev");
        assertTrue(mapper.map(node).isEmpty());
    }

    @Test
    void skipsUnsupportedNoteableType() throws Exception {
        String payload =
                "{"
                        + "\"user\":{\"id\":11,\"username\":\"u\"},"
                        + "\"project\":{\"path_with_namespace\":\"a/b\"},"
                        + "\"object_attributes\":{\"id\":1,\"note\":\"x\","
                        + "  \"noteable_type\":\"Commit\",\"system\":false}"
                        + "}";
        JsonNode node = MAPPER.readTree(payload);
        GitLabInboundMapper mapper = new GitLabInboundMapper("gitlab-dev");
        assertTrue(mapper.map(node).isEmpty());
    }

    @Test
    void missingIidReturnsEmpty() throws Exception {
        String payload =
                "{"
                        + "\"user\":{\"id\":11,\"username\":\"u\"},"
                        + "\"project\":{\"path_with_namespace\":\"a/b\"},"
                        + "\"object_attributes\":{\"id\":1,\"note\":\"x\","
                        + "  \"noteable_type\":\"Issue\",\"system\":false}"
                        + "}";
        JsonNode node = MAPPER.readTree(payload);
        GitLabInboundMapper mapper = new GitLabInboundMapper("gitlab-dev");
        assertTrue(mapper.map(node).isEmpty());
    }
}
