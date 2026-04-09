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
package io.agentscope.spring.boot.agui.common;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import java.lang.reflect.Method;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotationUtils;

/**
 * A Scanner class for automatically registering Agent beans with the AguiAgentRegistry.
 *
 * <p>This configuration scans all Agent beans in the application context and registers them with
 * the registry. It supports both singleton and prototype scoped beans:
 *
 * <ul>
 *   <li><b>Singleton beans:</b> The bean instance is registered directly
 *   <li><b>Prototype beans:</b> A factory is registered that creates new instances per request
 *       (thread-safe)
 * </ul>
 *
 * <p><b>Agent ID Resolution:</b>
 *
 * <ol>
 *   <li>{@link AguiAgentId} annotation on the bean method or class
 *   <li>Bean name (default)
 * </ol>
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * // Singleton bean (shared instance)
 * @Bean
 * public Agent chatAgent() {
 *     return ReActAgent.builder().name("Chat").model(model).build();
 * }
 *
 * // Prototype bean (new instance per request - thread-safe)
 * @Bean
 * @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
 * public Agent isolatedAgent() {
 *     return ReActAgent.builder().name("Isolated").model(model).build();
 * }
 *
 * // Custom agent ID
 * @Bean
 * @AguiAgentId("custom-id")
 * public Agent myAgent() {
 *     return ReActAgent.builder().name("Custom").model(model).build();
 * }
 * }</pre>
 */
public class AguiAgentAutoRegistration implements BeanFactoryAware, InitializingBean {

    private static final Logger logger = LoggerFactory.getLogger(AguiAgentAutoRegistration.class);

    private ConfigurableListableBeanFactory beanFactory;

    private final AguiAgentRegistry registry;

    /**
     * Creates a new AguiAgentAutoRegistration.
     *
     * @param registry The agent registry
     */
    public AguiAgentAutoRegistration(AguiAgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof ConfigurableListableBeanFactory) {
            this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
        }
    }

    /**
     * Registers all Agent beans with the AguiAgentRegistry.
     *
     * <p>This method is called after the registry is created and scans for all Agent beans.
     */
    protected void aguiAgentAutoRegistrar() {
        if (beanFactory == null) {
            logger.warn("BeanFactory is not available, skipping auto-registration");
            return;
        }

        Map<String, Agent> agentBeans = beanFactory.getBeansOfType(Agent.class);

        for (Map.Entry<String, Agent> entry : agentBeans.entrySet()) {
            String beanName = entry.getKey();
            Agent agent = entry.getValue();

            // Determine agent ID
            String agentId = resolveAgentId(beanName);

            // Skip if already registered (manual registration takes priority)
            if (registry.hasAgent(agentId)) {
                logger.debug("Agent '{}' already registered, skipping auto-registration", agentId);
                continue;
            }

            // Check bean scope
            boolean isPrototype = isPrototypeBean(beanName);

            if (isPrototype) {
                // Register factory for prototype beans (thread-safe: new instance per call)
                registry.registerFactory(agentId, () -> beanFactory.getBean(beanName, Agent.class));
                logger.info(
                        "Auto-registered prototype agent '{}' (bean: {}) with factory",
                        agentId,
                        beanName);
            } else {
                // Register singleton directly
                registry.register(agentId, agent);
                logger.info("Auto-registered singleton agent '{}' (bean: {})", agentId, beanName);
            }
        }
    }

    /**
     * Resolve the agent ID for a bean.
     *
     * @param beanName The bean name
     * @return The agent ID
     */
    private String resolveAgentId(String beanName) {
        // Try to find @AguiAgentId annotation on the bean definition
        try {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            String factoryMethodName = bd.getFactoryMethodName();

            if (factoryMethodName != null && bd.getFactoryBeanName() != null) {
                // @Bean method - check for annotation on the method
                Object factoryBean = beanFactory.getBean(bd.getFactoryBeanName());
                for (Method method : factoryBean.getClass().getMethods()) {
                    if (method.getName().equals(factoryMethodName)) {
                        AguiAgentId annotation =
                                AnnotationUtils.findAnnotation(method, AguiAgentId.class);
                        if (annotation != null) {
                            return annotation.value();
                        }
                        break;
                    }
                }
            }

            // Check for annotation on the bean class
            Class<?> beanType = beanFactory.getType(beanName);
            if (beanType != null) {
                AguiAgentId annotation =
                        AnnotationUtils.findAnnotation(beanType, AguiAgentId.class);
                if (annotation != null) {
                    return annotation.value();
                }
            }
        } catch (Exception e) {
            logger.debug(
                    "Could not resolve agent ID annotation for bean '{}': {}",
                    beanName,
                    e.getMessage());
        }

        // Default to bean name
        return beanName;
    }

    /**
     * Check if a bean is prototype-scoped.
     *
     * @param beanName The bean name
     * @return true if the bean is prototype-scoped
     */
    private boolean isPrototypeBean(String beanName) {
        try {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            return bd.isPrototype();
        } catch (Exception e) {
            logger.debug("Could not determine scope for bean '{}': {}", beanName, e.getMessage());
            return false;
        }
    }

    /**
     * Invoke {@link #aguiAgentAutoRegistrar()} after all properties are set.
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        this.aguiAgentAutoRegistrar();
    }
}
