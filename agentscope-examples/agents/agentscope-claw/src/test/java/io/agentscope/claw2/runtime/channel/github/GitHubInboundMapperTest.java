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
package io.agentscope.claw2.runtime.channel.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.claw2.runtime.channel.InboundMessage;
import io.agentscope.claw2.runtime.channel.PeerKind;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitHubInboundMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsIssueCommentCreated() throws Exception {
        String payload =
                "{"
                        + "\"action\":\"created\","
                        + "\"repository\":{"
                        + "  \"full_name\":\"acme/widgets\","
                        + "  \"owner\":{\"login\":\"acme\"}"
                        + "},"
                        + "\"issue\":{\"number\":42},"
                        + "\"comment\":{"
                        + "  \"id\":1001,"
                        + "  \"body\":\"@bot please review\","
                        + "  \"user\":{\"id\":777,\"login\":\"alice\"}"
                        + "}"
                        + "}";
        JsonNode node = MAPPER.readTree(payload);

        GitHubInboundMapper mapper = new GitHubInboundMapper("github-acme");
        Optional<InboundMessage> result = mapper.map("issue_comment", node);
        assertTrue(result.isPresent());
        InboundMessage in = result.get();
        assertEquals("github-acme", in.channelId());
        assertEquals(PeerKind.THREAD, in.peer().kind());
        assertEquals("acme/widgets#42", in.peer().id());
        assertEquals("alice", in.senderId());
        assertEquals("acme", in.accountId());
        assertEquals("@bot please review", in.messages().get(0).getTextContent());

        assertEquals(1001L, GitHubInboundMapper.extractCommentId(node).orElseThrow());
        assertEquals(777L, GitHubInboundMapper.extractCommenterId(node).orElseThrow());
    }

    @Test
    void mapsPullRequestReviewComment() throws Exception {
        String payload =
                "{"
                        + "\"action\":\"created\","
                        + "\"repository\":{"
                        + "  \"full_name\":\"acme/widgets\","
                        + "  \"owner\":{\"login\":\"acme\"}"
                        + "},"
                        + "\"pull_request\":{\"number\":7},"
                        + "\"comment\":{"
                        + "  \"id\":2002,"
                        + "  \"body\":\"line 12 looks off\","
                        + "  \"user\":{\"id\":888,\"login\":\"bob\"}"
                        + "}"
                        + "}";
        JsonNode node = MAPPER.readTree(payload);

        GitHubInboundMapper mapper = new GitHubInboundMapper("github-acme");
        Optional<InboundMessage> result = mapper.map("pull_request_review_comment", node);
        assertTrue(result.isPresent());
        InboundMessage in = result.get();
        assertEquals("acme/widgets#7", in.peer().id());
        assertEquals("bob", in.senderId());
    }

    @Test
    void skipsNonCreatedActions() throws Exception {
        String payload =
                "{\"action\":\"edited\",\"repository\":{\"full_name\":\"a/b\","
                    + "\"owner\":{\"login\":\"a\"}},\"issue\":{\"number\":1},"
                    + "\"comment\":{\"id\":1,\"body\":\"x\",\"user\":{\"id\":1,\"login\":\"u\"}}}";
        JsonNode node = MAPPER.readTree(payload);
        GitHubInboundMapper mapper = new GitHubInboundMapper("github-acme");
        assertTrue(mapper.map("issue_comment", node).isEmpty());
    }

    @Test
    void skipsUnknownEventType() throws Exception {
        String payload = "{\"action\":\"created\"}";
        JsonNode node = MAPPER.readTree(payload);
        GitHubInboundMapper mapper = new GitHubInboundMapper("github-acme");
        assertTrue(mapper.map("push", node).isEmpty());
    }

    @Test
    void emptyCommentMissingFieldsReturnsEmpty() throws Exception {
        String payload = "{\"action\":\"created\",\"repository\":{}}";
        JsonNode node = MAPPER.readTree(payload);
        GitHubInboundMapper mapper = new GitHubInboundMapper("github-acme");
        assertTrue(mapper.map("issue_comment", node).isEmpty());
    }
}
