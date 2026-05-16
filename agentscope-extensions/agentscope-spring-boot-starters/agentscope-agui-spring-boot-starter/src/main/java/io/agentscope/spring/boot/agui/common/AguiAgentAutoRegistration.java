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
 * Spark-override of the upstream AguiAgentAutoRegistration.
 *
 * <p>Fixes prototype bean handling: the upstream version uses
 * {@code beanFactory.getBeansOfType(Agent.class)} which eagerly instantiates
 * ALL Agent beans including prototypes. This fails when prototype factory methods
 * depend on request-scoped context (e.g., {@code AguiRequestContext}) that is not
 * available at application startup.
 *
 * <p>This version scans bean definitions without instantiating prototype beans,
 * registering only a factory supplier for them.
 */
public class AguiAgentAutoRegistration implements BeanFactoryAware, InitializingBean {

    private static final Logger logger = LoggerFactory.getLogger(AguiAgentAutoRegistration.class);

    private ConfigurableListableBeanFactory beanFactory;

    private final AguiAgentRegistry registry;

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
     * <p>Unlike the upstream version, this does NOT call {@code getBeansOfType(Agent.class)}
     * which would eagerly instantiate prototype beans. Instead, it iterates over bean
     * definitions and resolves types without instantiation.
     */
    protected void aguiAgentAutoRegistrar() {
        if (beanFactory == null) {
            logger.warn("BeanFactory is not available, skipping auto-registration");
            return;
        }

        String[] beanNames = beanFactory.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            // Resolve bean type without instantiation
            Class<?> beanType;
            try {
                beanType = beanFactory.getType(beanName);
            } catch (Exception e) {
                logger.debug("Could not resolve type for bean '{}': {}", beanName, e.getMessage());
                continue;
            }

            if (beanType == null || !Agent.class.isAssignableFrom(beanType)) {
                continue;
            }

            // Determine agent ID
            String agentId = resolveAgentId(beanName);

            // Skip if already registered (manual registration takes priority)
            if (registry.hasAgent(agentId)) {
                logger.debug("Agent '{}' already registered, skipping auto-registration", agentId);
                continue;
            }

            boolean isPrototype = isPrototypeBean(beanName);

            if (isPrototype) {
                // Register factory for prototype beans WITHOUT creating an instance.
                // The factory will be invoked per-request when the HTTP context is available.
                registry.registerFactory(agentId, () -> beanFactory.getBean(beanName, Agent.class));
                logger.info(
                        "Auto-registered prototype agent '{}' (bean: {}) with factory",
                        agentId,
                        beanName);
            } else {
                // Register singleton directly (safe to instantiate at startup)
                Agent agent = beanFactory.getBean(beanName, Agent.class);
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
