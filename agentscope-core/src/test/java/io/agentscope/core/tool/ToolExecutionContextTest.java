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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for ToolExecutionContext with List<ContextStore> architecture */
class ToolExecutionContextTest {

    /** POJO for testing type-based storage */
    static class UserContext {
        private String userId;
        private String sessionId;
        private Map<String, String> permissions;

        public UserContext() {}

        public UserContext(String userId, String sessionId) {
            this.userId = userId;
            this.sessionId = sessionId;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public Map<String, String> getPermissions() {
            return permissions;
        }

        public void setPermissions(Map<String, String> permissions) {
            this.permissions = permissions;
        }
    }

    static class DatabaseConfig {
        private String url;
        private int maxConnections;

        public DatabaseConfig(String url, int maxConnections) {
            this.url = url;
            this.maxConnections = maxConnections;
        }

        public String getUrl() {
            return url;
        }

        public int getMaxConnections() {
            return maxConnections;
        }
    }

    @Test
    void testSingletonPattern() {
        // Singleton: one instance per type
        UserContext userCtx = new UserContext("user123", "session456");
        DatabaseConfig dbConfig = new DatabaseConfig("jdbc:mysql://localhost", 10);

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .register(userCtx) // Register by actual type
                        .register(dbConfig)
                        .build();

        // Retrieve by type - gets the same instance!
        UserContext retrieved = context.get(UserContext.class);
        DatabaseConfig retrievedDb = context.get(DatabaseConfig.class);

        assertSame(userCtx, retrieved);
        assertSame(dbConfig, retrievedDb);
        assertEquals("user123", retrieved.getUserId());
        assertEquals(10, retrievedDb.getMaxConnections());
    }

    @Test
    void testMultiInstancePattern() {
        // Multiple instances of same type, distinguished by keys
        UserContext admin = new UserContext("admin", "session1");
        UserContext guest = new UserContext("guest", "session2");

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .register("admin", admin)
                        .register("guest", guest)
                        .build();

        // Retrieve by key and type
        UserContext adminCtx = context.get("admin", UserContext.class);
        UserContext guestCtx = context.get("guest", UserContext.class);

        assertSame(admin, adminCtx);
        assertSame(guest, guestCtx);
        assertEquals("admin", adminCtx.getUserId());
        assertEquals("guest", guestCtx.getUserId());
    }

    @Test
    void testExplicitTypeRegistration() {
        // Register by interface/superclass type
        UserContext impl = new UserContext("user123", "session456");

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .register(UserContext.class, impl) // Explicit type
                        .build();

        UserContext retrieved = context.get(UserContext.class);
        assertSame(impl, retrieved);
    }

    @Test
    void testKeyedExplicitTypeRegistration() {
        // Register with both key and explicit type
        UserContext admin = new UserContext("admin", "session1");
        UserContext guest = new UserContext("guest", "session2");

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .register("admin", UserContext.class, admin)
                        .register("guest", UserContext.class, guest)
                        .build();

        assertEquals("admin", context.get("admin", UserContext.class).getUserId());
        assertEquals("guest", context.get("guest", UserContext.class).getUserId());
    }

    @Test
    void testMixedSingletonAndMultiInstance() {
        // Mix singleton and multi-instance for same type
        DatabaseConfig defaultDb = new DatabaseConfig("jdbc:mysql://default", 10);
        DatabaseConfig replicaDb = new DatabaseConfig("jdbc:mysql://replica", 5);

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .register(defaultDb) // Singleton (default key)
                        .register("replica", replicaDb) // Keyed instance
                        .build();

        // Singleton retrieval
        DatabaseConfig def = context.get(DatabaseConfig.class);
        assertSame(defaultDb, def);

        // Keyed retrieval
        DatabaseConfig replica = context.get("replica", DatabaseConfig.class);
        assertSame(replicaDb, replica);
    }

    @Test
    void testContainsMethods() {
        UserContext userCtx = new UserContext("user123", "session456");

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .register(userCtx)
                        .register("admin", new UserContext("admin", "session1"))
                        .build();

        // Type-based contains
        assertTrue(context.contains(UserContext.class));
        assertFalse(context.contains(DatabaseConfig.class));

        // Key-based contains
        assertTrue(context.contains("admin", UserContext.class));
        assertFalse(context.contains("guest", UserContext.class));
    }

    @Test
    void testPriorityChainMerge() {
        // Toolkit level - default context
        DatabaseConfig toolkitDb = new DatabaseConfig("jdbc:mysql://toolkit", 10);
        ToolExecutionContext toolkitCtx =
                ToolExecutionContext.builder().register(toolkitDb).build();

        // Agent level - override with user context
        UserContext agentUser = new UserContext("user123", "session456");
        ToolExecutionContext agentCtx = ToolExecutionContext.builder().register(agentUser).build();

        // Call level - most specific
        UserContext callUser = new UserContext("user999", "session999");
        ToolExecutionContext callCtx = ToolExecutionContext.builder().register(callUser).build();

        // Merge: callCtx > agentCtx > toolkitCtx
        ToolExecutionContext merged = ToolExecutionContext.merge(callCtx, agentCtx, toolkitCtx);

        // Call context takes precedence for UserContext
        UserContext mergedUser = merged.get(UserContext.class);
        assertSame(callUser, mergedUser);
        assertEquals("user999", mergedUser.getUserId());

        // Database config comes from toolkit
        DatabaseConfig mergedDb = merged.get(DatabaseConfig.class);
        assertSame(toolkitDb, mergedDb);
    }

    @Test
    void testEmptyContext() {
        ToolExecutionContext empty = ToolExecutionContext.empty();

        assertNull(empty.get(UserContext.class));
        assertNull(empty.get("admin", UserContext.class));
        assertFalse(empty.contains(UserContext.class));
        assertFalse(empty.contains("admin", UserContext.class));
    }

    @Test
    void testNullRetrievals() {
        UserContext userCtx = new UserContext("user123", "session456");

        ToolExecutionContext context = ToolExecutionContext.builder().register(userCtx).build();

        // Type doesn't exist
        assertNull(context.get(DatabaseConfig.class));

        // Key doesn't exist
        assertNull(context.get("nonexistent", UserContext.class));
    }

    @Test
    void testStoreList() {
        DatabaseConfig dbConfig = new DatabaseConfig("jdbc:mysql://localhost", 10);

        ToolExecutionContext context = ToolExecutionContext.builder().register(dbConfig).build();

        // Should have one store (SimpleContextStore)
        assertEquals(1, context.getStores().size());
        assertTrue(context.getStores().get(0) instanceof DefaultContextStore);
    }

    @Test
    void testMultipleStores() {
        // Create first store with UserContext
        DefaultContextStore store1 =
                DefaultContextStore.builder()
                        .register(new UserContext("user1", "session1"))
                        .build();

        // Create second store with DatabaseConfig
        DefaultContextStore store2 =
                DefaultContextStore.builder()
                        .register(new DatabaseConfig("jdbc:mysql://localhost", 10))
                        .build();

        // Add both stores to context
        ToolExecutionContext context =
                ToolExecutionContext.builder().addStore(store1).addStore(store2).build();

        // Can retrieve from both stores
        assertNotNull(context.get(UserContext.class));
        assertNotNull(context.get(DatabaseConfig.class));
        assertEquals(2, context.getStores().size());
    }

    @Test
    void testStorePriority() {
        // First store has UserContext with userId "first"
        DefaultContextStore store1 =
                DefaultContextStore.builder()
                        .register(new UserContext("first", "session1"))
                        .build();

        // Second store has UserContext with userId "second"
        DefaultContextStore store2 =
                DefaultContextStore.builder()
                        .register(new UserContext("second", "session2"))
                        .build();

        // First store in list takes precedence
        ToolExecutionContext context =
                ToolExecutionContext.builder().addStore(store1).addStore(store2).build();

        UserContext retrieved = context.get(UserContext.class);
        assertEquals("first", retrieved.getUserId());
    }
}
