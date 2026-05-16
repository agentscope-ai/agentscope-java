package io.agentscope.core.mcp.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for HandlerRegistry.
 */
class HandlerRegistryTest {

    private HandlerRegistry registry;
    private TestMethodHandler testHandler;

    @BeforeEach
    void setUp() {
        registry = new HandlerRegistry();
        testHandler = new TestMethodHandler();
    }

    @Test
    void testRegisterAndGet() {
        registry.register("test/method", testHandler);
        assertTrue(registry.get("test/method").isPresent());
        assertEquals(testHandler, registry.get("test/method").get());
    }

    @Test
    void testGetNonexistent() {
        assertFalse(registry.get("nonexistent").isPresent());
    }

    @Test
    void testHas() {
        registry.register("test/method", testHandler);
        assertTrue(registry.has("test/method"));
        assertFalse(registry.has("nonexistent"));
    }

    @Test
    void testUnregister() {
        registry.register("test/method", testHandler);
        assertTrue(registry.has("test/method"));
        registry.unregister("test/method");
        assertFalse(registry.has("test/method"));
    }

    @Test
    void testGetAll() {
        TestMethodHandler handler1 = new TestMethodHandler();
        TestMethodHandler handler2 = new TestMethodHandler();
        registry.register("method1", handler1);
        registry.register("method2", handler2);

        Map<String, MethodHandler> all = registry.getAll();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("method1"));
        assertTrue(all.containsKey("method2"));
    }

    @Test
    void testClear() {
        registry.register("method1", testHandler);
        registry.register("method2", testHandler);
        assertEquals(2, registry.getAll().size());

        registry.clear();
        assertEquals(0, registry.getAll().size());
    }

    private static class TestMethodHandler extends AbstractMethodHandler {

        @Override
        public String getMethod() {
            return "test/method";
        }

        @Override
        public Object handle(Object params) {
            return params;
        }
    }
}
