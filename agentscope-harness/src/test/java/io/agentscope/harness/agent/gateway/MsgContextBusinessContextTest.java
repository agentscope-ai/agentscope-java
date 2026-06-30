package io.agentscope.harness.agent.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MsgContextBusinessContextTest {

    private static final MsgContext BASE =
            new MsgContext(
                    "chatui",
                    null,
                    "room-1",
                    null,
                    null,
                    Map.of("agentId", "a1"),
                    "user-1",
                    Map.of());

    @Test
    void defaultContext_hasEmptyBusinessContext() {
        MsgContext ctx = MsgContext.defaultContext();
        assertNotNull(ctx.businessContext());
        assertTrue(ctx.businessContext().isEmpty());
    }

    @Test
    void canonicalKey_doesNotIncludeBusinessContext() {
        MsgContext withCtx =
                new MsgContext(
                        "chatui",
                        null,
                        "room-1",
                        null,
                        null,
                        Map.of("agentId", "a1"),
                        "user-1",
                        Map.of("tenantId", "tenant-123", "modelConfigId", "gpt-4"));
        MsgContext withoutCtx =
                new MsgContext(
                        "chatui",
                        null,
                        "room-1",
                        null,
                        null,
                        Map.of("agentId", "a1"),
                        "user-1",
                        Map.of());
        assertEquals(withoutCtx.canonicalKey(), withCtx.canonicalKey());
    }

    @Test
    void canonicalKey_notAffectedByBusinessContextContent() {
        MsgContext ctxA =
                new MsgContext(
                        "chatui",
                        null,
                        "room-1",
                        null,
                        null,
                        Map.of("agentId", "a1"),
                        null,
                        Map.of("tenantId", "tenant-A"));
        MsgContext ctxB =
                new MsgContext(
                        "chatui",
                        null,
                        "room-1",
                        null,
                        null,
                        Map.of("agentId", "a1"),
                        null,
                        Map.of("tenantId", "tenant-B"));
        assertEquals(ctxA.canonicalKey(), ctxB.canonicalKey());
    }

    @Test
    void businessContext_doesNotAffectSessionKey() {
        String keyWithBiz =
                new MsgContext(
                                "chatui",
                                null,
                                "room-1",
                                null,
                                null,
                                Map.of("agentId", "a1"),
                                null,
                                Map.of("key", "value"))
                        .canonicalKey();
        String keyWithoutBiz =
                new MsgContext(
                                "chatui",
                                null,
                                "room-1",
                                null,
                                null,
                                Map.of("agentId", "a1"),
                                null,
                                Map.of())
                        .canonicalKey();
        assertEquals(keyWithoutBiz, keyWithBiz);
    }

    @Test
    void canonicalKey_extra_stillAffectsSessionKey() {
        MsgContext withExtra =
                new MsgContext(
                        "chatui",
                        null,
                        "room-1",
                        null,
                        null,
                        Map.of("agentId", "a1", "custom", "x"),
                        null,
                        Map.of());
        MsgContext withoutExtra =
                new MsgContext(
                        "chatui",
                        null,
                        "room-1",
                        null,
                        null,
                        Map.of("agentId", "a1"),
                        null,
                        Map.of());
        assertNotSame(withExtra.canonicalKey(), withoutExtra.canonicalKey());
    }

    @Test
    void withBusinessContext_mergesCorrectly() {
        MsgContext ctx = BASE.withBusinessContext(Map.of("tenantId", "tenant-123"));
        assertEquals("tenant-123", ctx.businessContext().get("tenantId"));
        assertEquals(1, ctx.businessContext().size());
    }

    @Test
    void withBusinessContext_mergesMultipleParams() {
        MsgContext ctx =
                BASE.withBusinessContext(
                        Map.of("tenantId", "tenant-123", "modelConfigId", "gpt-4"));
        assertEquals("tenant-123", ctx.businessContext().get("tenantId"));
        assertEquals("gpt-4", ctx.businessContext().get("modelConfigId"));
        assertEquals(2, ctx.businessContext().size());
    }

    @Test
    void withBusinessContext_doesNotMutateOriginal() {
        MsgContext original =
                new MsgContext(
                        "chatui",
                        null,
                        "room-1",
                        null,
                        null,
                        Map.of("agentId", "a1"),
                        null,
                        Map.of());
        original.withBusinessContext(Map.of("tenantId", "tenant-123"));
        assertTrue(original.businessContext().isEmpty());
    }

    @Test
    void withBusinessContext_null_returnsSelf() {
        MsgContext result = BASE.withBusinessContext(null);
        assertSame(BASE, result);
    }

    @Test
    void withBusinessContext_empty_returnsSelf() {
        MsgContext result = BASE.withBusinessContext(Map.of());
        assertSame(BASE, result);
    }

    @Test
    void withUserId_preservesBusinessContext() {
        MsgContext ctx =
                BASE.withBusinessContext(Map.of("tenantId", "tenant-123")).withUserId("other-user");
        assertEquals("tenant-123", ctx.businessContext().get("tenantId"));
        assertEquals("other-user", ctx.userId());
    }

    @Test
    void businessContext_isImmutable() {
        MsgContext ctx = BASE.withBusinessContext(Map.of("key", "value"));
        assertThrows(
                UnsupportedOperationException.class, () -> ctx.businessContext().put("new", "v"));
    }

    @Test
    void nullBusinessContext_defaultsToEmpty() {
        MsgContext ctx = new MsgContext("chatui", null, "room-1", null, null, Map.of(), null, null);
        assertNotNull(ctx.businessContext());
        assertTrue(ctx.businessContext().isEmpty());
    }

    @Test
    void sixArgConstructor_hasEmptyBusinessContext() {
        MsgContext ctx = new MsgContext("chatui", null, "room-1", null, null, Map.of());
        assertNotNull(ctx.businessContext());
        assertTrue(ctx.businessContext().isEmpty());
    }

    @Test
    void sevenArgConstructor_hasEmptyBusinessContext() {
        MsgContext ctx = new MsgContext("chatui", null, "room-1", null, null, Map.of(), "user-1");
        assertNotNull(ctx.businessContext());
        assertTrue(ctx.businessContext().isEmpty());
    }
}
