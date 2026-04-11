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
package io.agentscope.spring.boot.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;

/**
 * Scanner and registrar for AgentScope tools.
 *
 * <p>This component hooks into the Spring lifecycle after all singletons are instantiated.
 * It scans the application context for beans containing methods annotated with {@link Tool}
 * and registers them with the AgentScope {@link Toolkit}.
 *
 * <p>Built-in fault tolerance includes fail-fast validation for globally unique tool names
 * and graceful handling of Spring AOP proxies and {@code @Lazy} initialized beans.
 */
public class AgentscopeToolRegistrar
        implements SmartInitializingSingleton, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeToolRegistrar.class);

    private ApplicationContext applicationContext;
    private final Toolkit toolkit;

    public AgentscopeToolRegistrar(Toolkit toolkit) {
        this.toolkit = toolkit;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        log.info("Start scanning AgentScope @Tool in Spring Context...");

        if (!(applicationContext instanceof ConfigurableApplicationContext)) {
            log.warn("ApplicationContext is not ConfigurableApplicationContext, skip scanning.");
            return;
        }

        ConfigurableListableBeanFactory beanFactory =
                ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        String[] beanNames = beanFactory.getBeanDefinitionNames();

        // Global tracker to ensure tool names are unique across the application
        Set<String> registeredToolNames = new HashSet<>();

        for (String beanName : beanNames) {
            // Skip non-singletons and infrastructure beans to prevent memory leaks and premature
            // initialization
            BeanDefinition beanDefinition = null;
            if (beanFactory.containsBeanDefinition(beanName)) {
                beanDefinition = beanFactory.getBeanDefinition(beanName);
                if (!beanDefinition.isSingleton()
                        || beanDefinition.getRole() == BeanDefinition.ROLE_INFRASTRUCTURE) {
                    continue;
                }
            }

            // Safely resolve bean type without triggering instantiation
            Class<?> beanType = beanFactory.getType(beanName, false);
            if (beanType == null || beanType.getName().startsWith("org.springframework.")) {
                continue;
            }

            // Unwrap CGLIB proxy classes to get the actual user class
            Class<?> originalClass = ClassUtils.getUserClass(beanType);

            // Scan for @Tool annotations (supports interfaces and superclasses)
            Map<Method, Tool> annotatedMethods =
                    MethodIntrospector.selectMethods(
                            originalClass,
                            (MethodIntrospector.MetadataLookup<Tool>)
                                    method -> AnnotationUtils.findAnnotation(method, Tool.class));

            // Process beans that actually contain tool methods
            if (!annotatedMethods.isEmpty()) {
                // Fail-fast validation: Check for tool name collisions before instantiating the
                // bean
                for (Map.Entry<Method, Tool> entry : annotatedMethods.entrySet()) {
                    Method method = entry.getKey();
                    Tool toolAnn = entry.getValue();
                    String toolName = toolAnn.name().isEmpty() ? method.getName() : toolAnn.name();

                    if (!registeredToolNames.add(toolName)) {
                        throw new BeanInitializationException(
                                String.format(
                                        "Duplicate AgentScope tool name '%s' found in Spring Bean"
                                                + " '%s'. Tool names must be unique globally.",
                                        toolName, beanName));
                    }
                }

                // Transparently warn developers if a @Lazy bean is being eagerly initialized
                if (beanDefinition != null && beanDefinition.isLazyInit()) {
                    log.warn(
                            "Spring Bean '{}' is marked with @Lazy but contains @Tool methods. It"
                                + " is being forcefully initialized early by AgentScope to register"
                                + " tools.",
                            beanName);
                }

                try {
                    Object bean = applicationContext.getBean(beanName);

                    // Build the complete class hierarchy tree (including all superclasses and
                    // interfaces)
                    Set<Class<?>> hierarchy = new LinkedHashSet<>();
                    Class<?> current = originalClass;
                    while (current != null && current != Object.class) {
                        hierarchy.add(current);
                        current = current.getSuperclass();
                    }
                    hierarchy.addAll(ClassUtils.getAllInterfacesForClassAsSet(originalClass));

                    // Filter the hierarchy to find ONLY the classes/interfaces declaring @Tool.
                    Set<Class<?>> classesToScan = new LinkedHashSet<>();
                    for (Class<?> clazz : hierarchy) {
                        // Skip Java and Spring internal interfaces
                        if (clazz.getName().startsWith("java.")
                                || clazz.getName().startsWith("org.springframework.")) {
                            continue;
                        }

                        // Inspect the declared methods to verify if this class/interface contains
                        // tools
                        for (Method m : clazz.getDeclaredMethods()) {
                            if (m.isAnnotationPresent(Tool.class)) {
                                classesToScan.add(clazz);
                                break;
                            }
                        }
                    }

                    for (Class<?> clazzToScan : classesToScan) {
                        // Pass both the proxy instance (bean) and the specific declaring
                        // class/interface (clazzToScan).
                        // This ensures AgentScope extracts metadata (like @Tool) from the exact
                        // interface/superclass,
                        // while still routing actual method executions through the proxy to
                        // preserve Spring AOP aspects.
                        toolkit.registration().tool(bean, clazzToScan).apply();
                    }

                    log.info(
                            "Successfully registered Spring Bean '{}' (found {} tools) as"
                                    + " AgentScope Tool(s).",
                            beanName,
                            annotatedMethods.size());
                } catch (Exception e) {
                    throw new BeanInitializationException(
                            "Failed to register Spring Bean '" + beanName + "' as AgentScope Tool.",
                            e);
                }
            }
        }
        log.info(
                "Finished scanning AgentScope @Tool. Total tools registered: {}",
                registeredToolNames.size());
    }
}
