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

/**
 * Provider interface for resolving ToolExecutionContext from external sources.
 *
 * <p>This interface enables integration with dependency injection frameworks like Spring, allowing
 * tool execution contexts to be resolved from IoC containers, request scopes, thread-local storage,
 * or any other context management mechanism.
 *
 * <p><b>Spring Integration Example:</b>
 *
 * <pre>{@code
 * // 1. Define your context POJO with Spring scope
 * @Component
 * @Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
 * public class UserContext {
 *     private String userId;
 *     private String sessionId;
 *     private Map<String, String> permissions;
 *     // getters/setters...
 * }
 *
 * // 2. Implement ToolExecutionContextProvider
 * public class SpringContextProvider implements ToolExecutionContextProvider {
 *     private final ApplicationContext applicationContext;
 *
 *     public SpringContextProvider(ApplicationContext applicationContext) {
 *         this.applicationContext = applicationContext;
 *     }
 *
 *     @Override
 *     public <T> T resolveContext(Class<T> targetType) {
 *         try {
 *             // Try to resolve from Spring container
 *             if (targetType == ToolExecutionContext.class) {
 *                 // Convert Spring bean to ToolExecutionContext
 *                 UserContext userCtx = applicationContext.getBean(UserContext.class);
 *                 return (T) ToolExecutionContext.of(userCtx);
 *             } else {
 *                 // Direct bean resolution for custom types
 *                 return applicationContext.getBean(targetType);
 *             }
 *         } catch (Exception e) {
 *             return null; // Context not available
 *         }
 *     }
 * }
 *
 * // 3. Register provider in Spring configuration
 * @Configuration
 * public class AgentScopeConfig {
 *     @Bean
 *     public ToolExecutionContextProvider contextProvider(ApplicationContext ctx) {
 *         SpringContextProvider provider = new SpringContextProvider(ctx);
 *         ToolExecutionContext.setContextProvider(provider);
 *         return provider;
 *     }
 * }
 *
 * // 4. Use in tool methods - context auto-resolved from Spring!
 * @Tool(name = "query_db", description = "Query database")
 * public ToolResultBlock queryDb(
 *     @ToolParam(name = "query") String query,
 *     UserContext context  // Auto-resolved from Spring RequestScope!
 * ) {
 *     String userId = context.getUserId();
 *     // ...
 * }
 * }</pre>
 *
 * <p><b>ThreadLocal Example:</b>
 *
 * <pre>{@code
 * public class ThreadLocalContextProvider implements ToolExecutionContextProvider {
 *     private static final ThreadLocal<ToolExecutionContext> CONTEXT = new ThreadLocal<>();
 *
 *     public static void setContext(ToolExecutionContext ctx) {
 *         CONTEXT.set(ctx);
 *     }
 *
 *     public static void clear() {
 *         CONTEXT.remove();
 *     }
 *
 *     @Override
 *     public <T> T resolveContext(Class<T> targetType) {
 *         ToolExecutionContext ctx = CONTEXT.get();
 *         if (ctx == null) return null;
 *
 *         if (targetType == ToolExecutionContext.class) {
 *             return (T) ctx;
 *         } else {
 *             return ctx.as(targetType);
 *         }
 *     }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface ToolExecutionContextProvider {

    /**
     * Resolves a context instance of the specified type.
     *
     * <p>This method is called by the framework when a tool method parameter requires context
     * injection but no explicit context was provided at the call site. The implementation should
     * attempt to resolve the context from its managed source (Spring container, ThreadLocal, etc.)
     * and return an instance of the requested type.
     *
     * @param targetType The target type to resolve (ToolExecutionContext or custom POJO)
     * @param <T> The target type
     * @return The resolved context instance, or null if not available
     */
    <T> T resolveContext(Class<T> targetType);
}
