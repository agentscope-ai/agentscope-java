/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.agent.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.util.JsonUtils;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A2A durable HITL handoff value types")
class A2aHandoffTest {

    private static final String TOKEN = "not-for-logs-token-0123456789";

    @Test
    @DisplayName("Should round-trip every public HITL type through Jackson")
    void shouldRoundTripEveryPublicTypeThroughJackson() {
        A2aHandoff handoff = handoff();
        List<A2aHitlResponse> responses =
                List.of(
                        new A2aUserConfirmation(
                                "call-1",
                                true,
                                Map.of("city", "Shanghai"),
                                List.of(
                                        new PermissionRule(
                                                "weather",
                                                "city=Shanghai",
                                                PermissionBehavior.ALLOW,
                                                "user"))),
                        new A2aExternalToolResponse(
                                "call-2",
                                ToolResultState.SUCCESS,
                                List.of(TextBlock.builder().text("external result").build()),
                                Map.of("provider", "test")));

        A2aHandoff restored =
                JsonUtils.getJsonCodec()
                        .fromJson(JsonUtils.getJsonCodec().toJson(handoff), A2aHandoff.class);
        A2aHitlResponse[] restoredResponses =
                JsonUtils.getJsonCodec()
                        .fromJson(
                                JsonUtils.getJsonCodec().toJson(responses),
                                A2aHitlResponse[].class);

        assertEquals(handoff, restored);
        assertEquals(A2aHandoffType.USER_CONFIRM, restored.type());
        assertEquals(handoff.pendingTools().get(0), restored.pendingTools().get(0));
        assertEquals(2, restoredResponses.length);
        assertInstanceOf(A2aUserConfirmation.class, restoredResponses[0]);
        assertInstanceOf(A2aExternalToolResponse.class, restoredResponses[1]);
        assertEquals(
                "external result",
                ((TextBlock) ((A2aExternalToolResponse) restoredResponses[1]).outputBlocks().get(0))
                        .getText());
    }

    @Test
    @DisplayName("Should never reveal resume token through toString")
    void shouldRedactTokenFromToString() {
        String rendered = handoff().toString();

        assertFalse(rendered.contains(TOKEN));
        assertTrue(rendered.contains("<redacted>"));
    }

    @Test
    @DisplayName("Should never reveal token duplicated through any rendered handoff field")
    void shouldRedactTokenDuplicatedThroughNestedPendingToolFields() {
        A2aHandoff handoff =
                new A2aHandoff(
                        TOKEN,
                        TOKEN,
                        TOKEN,
                        A2aHandoffType.USER_CONFIRM,
                        Instant.parse("2030-01-01T00:00:00Z"),
                        List.of(
                                new A2aPendingTool(
                                        TOKEN,
                                        TOKEN,
                                        Map.of("secret", TOKEN, "nested", List.of(TOKEN)),
                                        TOKEN)),
                        TOKEN);

        String rendered = handoff.toString();

        assertFalse(rendered.contains(TOKEN));
        assertTrue(rendered.contains("resumeToken=<redacted>"));

        A2aHandoff enumCollision =
                new A2aHandoff(
                        "task",
                        "context",
                        "handoff",
                        A2aHandoffType.USER_CONFIRM,
                        Instant.parse("2030-01-01T00:00:00Z"),
                        List.of(new A2aPendingTool("call", "probe", Map.of(), null)),
                        "USER_CONFIRM");
        assertFalse(enumCollision.toString().contains(enumCollision.resumeToken()));
    }

    @Test
    @DisplayName("Should only parse complete client-local enhanced handoffs")
    void shouldOnlyParseClientLocalEnhancedHandoff() {
        Msg local = Msg.builder().textContent("confirm").build();
        local.getMetadata().put(MessageConstants.LOCAL_HANDOFF_METADATA_KEY, handoff());
        assertEquals(handoff(), A2aHandoff.tryFrom(local).orElseThrow());

        Msg wireOnly = Msg.builder().textContent("confirm").build();
        wireOnly.getMetadata().put(MessageConstants.HANDOFF_ID_METADATA_KEY, "handoff-1");
        wireOnly.getMetadata().put(MessageConstants.RESUME_TOKEN_METADATA_KEY, TOKEN);
        assertTrue(A2aHandoff.tryFrom(wireOnly).isEmpty());

        Msg malformedLocal = Msg.builder().textContent("confirm").build();
        malformedLocal
                .getMetadata()
                .put(MessageConstants.LOCAL_HANDOFF_METADATA_KEY, Map.of("handoffId", "partial"));
        assertTrue(A2aHandoff.tryFrom(malformedLocal).isEmpty());
    }

    @Test
    @DisplayName("Should reject blank identifiers, empty tools and duplicate tool ids")
    void shouldRejectMalformedContracts() {
        A2aPendingTool pending = pending("call-1", "weather");

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new A2aHandoff(
                                " ",
                                "context-1",
                                "handoff-1",
                                A2aHandoffType.USER_CONFIRM,
                                Instant.parse("2030-01-01T00:00:00Z"),
                                List.of(pending),
                                TOKEN));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new A2aHandoff(
                                "task-1",
                                "context-1",
                                "handoff-1",
                                A2aHandoffType.USER_CONFIRM,
                                Instant.parse("2030-01-01T00:00:00Z"),
                                List.of(),
                                TOKEN));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new A2aHandoff(
                                "task-1",
                                "context-1",
                                "handoff-1",
                                A2aHandoffType.USER_CONFIRM,
                                Instant.parse("2030-01-01T00:00:00Z"),
                                List.of(pending, pending),
                                TOKEN));
        assertThrows(
                IllegalArgumentException.class,
                () -> new A2aPendingTool(" ", "weather", Map.of(), null));
    }

    @Test
    @DisplayName("Should remove reserved credentials from external response metadata")
    void shouldRemoveCredentialsFromExternalResponseMetadata() {
        A2aExternalToolResponse response =
                new A2aExternalToolResponse(
                        "call-1",
                        ToolResultState.SUCCESS,
                        List.of(TextBlock.builder().text("ok").build()),
                        Map.of(
                                "safe",
                                "value",
                                MessageConstants.RESUME_TOKEN_METADATA_KEY,
                                TOKEN,
                                "nested",
                                Map.of(
                                        MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY,
                                        "nested-token")));

        assertEquals(Map.of("safe", "value", "nested", Map.of()), response.metadata());
        assertFalse(response.toString().contains(TOKEN));
        assertFalse(response.toString().contains("nested-token"));
    }

    @Test
    @DisplayName("Should deeply detach and freeze all public JSON-shaped values")
    void shouldDeeplyDetachAndFreezePublicValues() {
        Map<String, Object> pendingNested = new HashMap<>();
        pendingNested.put("items", new ArrayList<>(List.of("original")));
        Map<String, Object> pendingInput = new HashMap<>();
        pendingInput.put("nested", pendingNested);
        pendingInput.put("array", new String[] {"one", "two"});
        A2aPendingTool pending = new A2aPendingTool("call-1", "probe", pendingInput, "Allow?");

        Map<String, Object> modifiedNested = new HashMap<>();
        modifiedNested.put("items", new ArrayList<>(List.of("original")));
        A2aUserConfirmation confirmation =
                new A2aUserConfirmation(
                        "call-1", true, Map.of("nested", modifiedNested), List.of());

        Map<String, Object> blockNested = new HashMap<>();
        blockNested.put("items", new ArrayList<>(List.of("original")));
        ToolResultBlock block =
                ToolResultBlock.builder()
                        .id("call-1")
                        .name("probe")
                        .output(TextBlock.builder().text("ok").build())
                        .metadata(Map.of("nested", blockNested))
                        .state(ToolResultState.SUCCESS)
                        .build();
        Map<String, Object> responseNested = new HashMap<>();
        responseNested.put("items", new ArrayList<>(List.of("original")));
        A2aExternalToolResponse external =
                new A2aExternalToolResponse(
                        "call-1",
                        ToolResultState.SUCCESS,
                        List.of(block),
                        Map.of("nested", responseNested));
        String externalSnapshot = JsonUtils.getJsonCodec().toJson(external);

        ((List<String>) pendingNested.get("items")).add("mutated-original");
        ((List<String>) modifiedNested.get("items")).add("mutated-original");
        ((List<String>) blockNested.get("items")).add("mutated-original");
        ((List<String>) responseNested.get("items")).add("mutated-original");

        assertThrows(
                UnsupportedOperationException.class,
                () -> ((Map<String, Object>) pending.originalInput().get("nested")).put("x", 1));
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        ((List<Object>)
                                        ((Map<String, Object>)
                                                        confirmation.modifiedInput().get("nested"))
                                                .get("items"))
                                .add("x"));
        assertInstanceOf(List.class, pending.originalInput().get("array"));

        ToolResultBlock exposedBlock =
                assertInstanceOf(ToolResultBlock.class, external.outputBlocks().get(0));
        ((List<Object>)
                        ((Map<String, Object>) exposedBlock.getMetadata().get("nested"))
                                .get("items"))
                .add("mutated-accessor");
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        ((List<Object>)
                                        ((Map<String, Object>) external.metadata().get("nested"))
                                                .get("items"))
                                .add("mutated-accessor"));

        assertFalse(JsonUtils.getJsonCodec().toJson(pending).contains("mutated-original"));
        assertFalse(JsonUtils.getJsonCodec().toJson(confirmation).contains("mutated-original"));
        assertEquals(externalSnapshot, JsonUtils.getJsonCodec().toJson(external));
    }

    @Test
    @DisplayName("Should accept only immutable finite JSON number implementations")
    void shouldAcceptOnlyImmutableFiniteJsonNumbers() {
        A2aPendingTool accepted =
                new A2aPendingTool(
                        "call-1",
                        "probe",
                        Map.of(
                                "byte", Byte.valueOf((byte) 1),
                                "short", Short.valueOf((short) 2),
                                "int", Integer.valueOf(3),
                                "long", Long.valueOf(4),
                                "float", Float.valueOf(5.5F),
                                "double", Double.valueOf(6.5D),
                                "bigInteger", new BigInteger("12345678901234567890"),
                                "bigDecimal", new BigDecimal("1234567890.125")),
                        "Allow?");

        assertEquals(
                new BigInteger("12345678901234567890"), accepted.originalInput().get("bigInteger"));
        assertEquals(new BigDecimal("1234567890.125"), accepted.originalInput().get("bigDecimal"));

        AtomicInteger atomicInteger = new AtomicInteger(1);
        AtomicLong atomicLong = new AtomicLong(2);
        assertThrows(
                IllegalArgumentException.class,
                () -> new A2aPendingTool("call-1", "probe", Map.of("value", atomicInteger), null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new A2aUserConfirmation(
                                "call-1", true, Map.of("value", atomicLong), List.of()));
        atomicInteger.set(9);
        atomicLong.set(10);

        assertThrows(
                IllegalArgumentException.class,
                () -> new A2aPendingTool("call-1", "probe", Map.of("value", Double.NaN), null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new A2aPendingTool(
                                "call-1",
                                "probe",
                                Map.of("value", Double.POSITIVE_INFINITY),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new A2aPendingTool(
                                "call-1", "probe", Map.of("value", Float.NEGATIVE_INFINITY), null));
    }

    private A2aHandoff handoff() {
        return new A2aHandoff(
                "task-1",
                "context-1",
                "handoff-1",
                A2aHandoffType.USER_CONFIRM,
                Instant.parse("2030-01-01T00:00:00Z"),
                List.of(pending("call-1", "weather")),
                TOKEN);
    }

    private A2aPendingTool pending(String id, String name) {
        return new A2aPendingTool(id, name, Map.of("city", "Beijing"), "Allow weather?");
    }
}
