/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import io.agentcore.managed.client.ServiceClient;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Real Agent Service fixture with only the original test user token. */
public class ServiceClientAgentServiceSmoke {
    public static void main(String[] args) throws Exception {
        var client = ServiceClient.builder(args[0]).apiToken(System.getenv("MANAGED_AGENTS_TEST_TOKEN")).build();
        String session = args[1], turn = args[2], artifact = args[3];
        require(client.capabilities(session).path("capabilities").path("artifacts").asBoolean(), "artifacts capability");
        var snapshot = client.snapshot(session, turn, 10);
        require("completed".equals(snapshot.path("resources").path("turns").path("data").get(0).path("data").path("status").asText()), "snapshot terminal status");
        var totals = client.usage(session, turn).path("totals");
        require(totals.path("inputTokens").asInt() == 3 && totals.path("outputTokens").asInt() == 2, "authoritative usage");
        require(client.resources(session, turn, "artifacts", "", snapshot.path("snapshot_id").asText(), 10).path("data").size() == 1, "snapshot artifact page");
        require(artifact.equals(client.artifact(session, turn, artifact, "").path("data").path("data").path("artifact_id").asText()), "artifact metadata");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(client.artifactContent(session, turn, artifact)));
        require(args[4].equals(hash), "artifact bytes");
        var first = client.events(session, turn, "", 1).path("data").get(0);
        try (var events = client.stream(session, turn, first.path("cursor").asText())) {
            var next = events.nextEvent();
            require(next != null && !first.path("id").asText().equals(next.path("id").asText()), "SSE cursor replay");
        }
        System.out.println("Java public ServiceClient passed: real Agent Service snapshot, capabilities, usage, artifact metadata/bytes and SSE.");
    }
    private static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
