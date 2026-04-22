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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Integration tests for ToolExecutionContext with custom POJOs */
class ToolExecutionContextIntegrationTest {

    /** User-defined context POJO */
    static class UserContext {
        private String userId;
        private String sessionId;
        private Map<String, String> permissions;

        public UserContext() {}

        public UserContext(String userId, String sessionId) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.permissions = new HashMap<>();
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

        public String getPermission(String resource) {
            return permissions != null ? permissions.get(resource) : null;
        }

        public void addPermission(String resource, String access) {
            if (permissions == null) {
                permissions = new HashMap<>();
            }
            permissions.put(resource, access);
        }
    }

    /** Environment config POJO */
    static class EnvironmentConfig {
        private String environment;
        private String version;

        public EnvironmentConfig(String environment, String version) {
            this.environment = environment;
            this.version = version;
        }

        public String getEnvironment() {
            return environment;
        }

        public String getVersion() {
            return version;
        }
    }

    private String getText(ToolResultBlock result) {
        if (result == null || result.getOutput().isEmpty()) {
            return "";
        }
        if (result.getOutput().get(0) instanceof TextBlock) {
            return ((TextBlock) result.getOutput().get(0)).getText();
        }
        return "";
    }

    private boolean isError(ToolResultBlock result) {
        String text = getText(result);
        return text.startsWith("Error:");
    }

    /** Test tool that uses custom UserContext POJO */
    static class ContextAwareTool {

        @Tool(name = "check_permission", description = "Check user permission")
        public ToolResultBlock checkPermission(
                @ToolParam(name = "resource") String resource, UserContext context) {
            if (context == null) {
                return ToolResultBlock.error("No context provided");
            }

            String userId = context.getUserId();
            String permission = context.getPermission(resource);

            if (userId == null) {
                return ToolResultBlock.error("User not authenticated");
            }

            if (!"allow".equalsIgnoreCase(permission)) {
                return ToolResultBlock.error("Permission denied for user: " + userId);
            }

            return ToolResultBlock.text("Access granted for " + userId);
        }

        @Tool(name = "get_environment", description = "Get environment info")
        public ToolResultBlock getEnvironment(UserContext userCtx, EnvironmentConfig envConfig) {
            if (userCtx == null || envConfig == null) {
                return ToolResultBlock.text("Missing context");
            }

            return ToolResultBlock.text(
                    String.format(
                            "Environment: %s, Version: %s, Session: %s",
                            envConfig.getEnvironment(),
                            envConfig.getVersion(),
                            userCtx.getSessionId()));
        }
    }

    @Test
    void testContextInjectionInTool() {
        // Setup toolkit with default context
        UserContext toolkitUser = new UserContext("default-user", "default-session");
        toolkitUser.addPermission("db:read", "allow");

        ToolExecutionContext toolkitContext =
                ToolExecutionContext.builder().register(toolkitUser).build();

        ToolkitConfig config = ToolkitConfig.builder().defaultContext(toolkitContext).build();

        Toolkit toolkit = new Toolkit(config);
        toolkit.registration().tool(new ContextAwareTool()).apply();

        // Create agent-level context (overrides toolkit)
        UserContext agentUser = new UserContext("user123", "session456");
        agentUser.addPermission("db:read", "allow");

        ToolExecutionContext agentContext =
                ToolExecutionContext.builder().register(agentUser).build();

        // Create tool call
        Map<String, Object> input = new HashMap<>();
        input.put("resource", "db:read");

        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call1")
                        .name("check_permission")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        // Execute tool with context
        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(toolUse)
                                        .context(agentContext)
                                        .build())
                        .block();

        assertNotNull(result);
        assertFalse(isError(result));
        assertEquals("Access granted for user123", getText(result));
    }

    @Test
    void testContextMergingLevels() {
        // Toolkit level - provide EnvironmentConfig
        EnvironmentConfig toolkitEnv = new EnvironmentConfig("development", "1.0");
        UserContext toolkitUser = new UserContext("default", "default-session");

        ToolExecutionContext toolkitContext =
                ToolExecutionContext.builder().register(toolkitEnv).register(toolkitUser).build();

        ToolkitConfig config = ToolkitConfig.builder().defaultContext(toolkitContext).build();

        Toolkit toolkit = new Toolkit(config);
        toolkit.registration().tool(new ContextAwareTool()).apply();

        // Agent level - override EnvironmentConfig and provide user session
        EnvironmentConfig agentEnv = new EnvironmentConfig("production", "2.0");
        UserContext agentUser = new UserContext("agent-user", "session789");

        ToolExecutionContext agentContext =
                ToolExecutionContext.builder().register(agentEnv).register(agentUser).build();

        // Call level - override session
        UserContext callUser = new UserContext("call-user", "session-override");

        ToolExecutionContext callContext =
                ToolExecutionContext.builder().register(callUser).build();

        Map<String, Object> emptyInput = new HashMap<>();
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call2")
                        .name("get_environment")
                        .input(emptyInput)
                        .content(JsonUtils.getJsonCodec().toJson(emptyInput))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(toolUse)
                                        .context(
                                                ToolExecutionContext.merge(
                                                        callContext, agentContext))
                                        .build())
                        .block();

        assertNotNull(result);
        String text = getText(result);
        // Should use: call user's session (highest priority), agent's environment config
        assertTrue(text.contains("production")); // From agent context
        assertTrue(text.contains("2.0")); // From agent context
        assertTrue(text.contains("session-override")); // From call context
    }

    @Test
    void testPermissionDenied() {
        UserContext user = new UserContext("user456", "session123");
        user.addPermission("db:read", "deny");

        ToolExecutionContext context = ToolExecutionContext.builder().register(user).build();

        ToolkitConfig config = ToolkitConfig.builder().defaultContext(context).build();

        Toolkit toolkit = new Toolkit(config);
        toolkit.registration().tool(new ContextAwareTool()).apply();

        Map<String, Object> input = new HashMap<>();
        input.put("resource", "db:read");

        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call3")
                        .name("check_permission")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolUse).build()).block();

        assertNotNull(result);
        assertTrue(isError(result));
        assertTrue(getText(result).contains("Permission denied"));
    }

    @Test
    void testAsyncToolWithContext() {
        class AsyncTool {
            @Tool(name = "async_check", description = "Async check")
            public Mono<ToolResultBlock> asyncCheck(
                    @ToolParam(name = "value") String value, UserContext context) {
                return Mono.fromCallable(
                        () -> {
                            String userId = context != null ? context.getUserId() : "unknown";
                            return ToolResultBlock.text(
                                    String.format("User %s checked: %s", userId, value));
                        });
            }
        }

        UserContext user = new UserContext("async-user", "async-session");
        ToolExecutionContext context = ToolExecutionContext.builder().register(user).build();

        ToolkitConfig config = ToolkitConfig.builder().defaultContext(context).build();

        Toolkit toolkit = new Toolkit(config);
        toolkit.registration().tool(new AsyncTool()).apply();

        Map<String, Object> input = new HashMap<>();
        input.put("value", "test123");

        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call4")
                        .name("async_check")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolUse).build()).block();

        assertNotNull(result);
        String text = getText(result);
        assertTrue(text.contains("async-user"));
        assertTrue(text.contains("test123"));
    }

    @Test
    void testMultipleContextTypes() {
        // Tool that needs multiple context types
        class MultiContextTool {
            @Tool(name = "complex_operation", description = "Uses multiple contexts")
            public ToolResultBlock complexOp(
                    UserContext user,
                    EnvironmentConfig env,
                    @ToolParam(name = "action") String action) {
                if (user == null || env == null) {
                    return ToolResultBlock.error("Missing required context");
                }

                return ToolResultBlock.text(
                        String.format(
                                "Action: %s, User: %s, Env: %s, Version: %s",
                                action, user.getUserId(), env.getEnvironment(), env.getVersion()));
            }
        }

        UserContext user = new UserContext("multi-user", "multi-session");
        EnvironmentConfig env = new EnvironmentConfig("staging", "3.0");

        ToolExecutionContext context =
                ToolExecutionContext.builder().register(user).register(env).build();

        ToolkitConfig config = ToolkitConfig.builder().defaultContext(context).build();

        Toolkit toolkit = new Toolkit(config);
        toolkit.registration().tool(new MultiContextTool()).apply();

        Map<String, Object> input = new HashMap<>();
        input.put("action", "deploy");

        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call5")
                        .name("complex_operation")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolUse).build()).block();

        assertNotNull(result);
        String text = getText(result);
        assertTrue(text.contains("deploy"));
        assertTrue(text.contains("multi-user"));
        assertTrue(text.contains("staging"));
        assertTrue(text.contains("3.0"));
    }

    @Test
    void testKeyedContextRetrieval() {
        // Register multiple instances of same type with different keys
        UserContext admin = new UserContext("admin", "admin-session");
        admin.addPermission("db:write", "allow");

        UserContext guest = new UserContext("guest", "guest-session");
        guest.addPermission("db:read", "allow");

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .register("admin", admin)
                        .register("guest", guest)
                        .build();

        // Verify both are stored
        UserContext retrievedAdmin = context.get("admin", UserContext.class);
        UserContext retrievedGuest = context.get("guest", UserContext.class);

        assertNotNull(retrievedAdmin);
        assertNotNull(retrievedGuest);
        assertEquals("admin", retrievedAdmin.getUserId());
        assertEquals("guest", retrievedGuest.getUserId());
    }
}
