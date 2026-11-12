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
package io.agentcore.managed.server.runtime.managed;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Opt-in JDBC and scheduler diagnostics; no SQL text, credentials or model payloads are logged. */
@Configuration(proxyBeanMethods = false)
public class ManagedObservationConfiguration {
    @Bean
    static BeanPostProcessor managedObservationSources(Environment environment) {
        int every =
                environment.getProperty(
                        "managed-agents.runtime.observation-sample-every", Integer.class, 0);
        ManagedExecutionTrace.configure(every);
        return new BeanPostProcessor() {
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (every == 0 || !(bean instanceof DataSource)) return bean;
                var factory = new ProxyFactory(bean);
                factory.setProxyTargetClass(true);
                factory.addAdvice(
                        (MethodInterceptor)
                                invocation -> {
                                    if (!invocation.getMethod().getName().equals("getConnection"))
                                        return invocation.proceed();
                                    try (var ignored = ManagedExecutionTrace.span("sql_acquire")) {
                                        return connection((Connection) invocation.proceed());
                                    }
                                });
                return factory.getProxy();
            }
        };
    }

    static Connection connection(Connection target) {
        // Only wrap connections acquired by sampled work; all application semantics delegate.
        if (ManagedExecutionTrace.current() == null) return target;
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            String name = method.getName();
                            try (var ignored =
                                    ManagedExecutionTrace.span(
                                            name.equals("commit") || name.equals("rollback")
                                                    ? "sql_transaction_end"
                                                    : "sql_connection")) {
                                Object result = invoke(target, method, args);
                                if (result instanceof Statement statement)
                                    return statement(statement);
                                return result;
                            }
                        });
    }

    private static Statement statement(Statement target) {
        Class<?> type =
                target instanceof CallableStatement
                        ? CallableStatement.class
                        : target instanceof PreparedStatement
                                ? PreparedStatement.class
                                : Statement.class;
        return (Statement)
                Proxy.newProxyInstance(
                        Statement.class.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, args) -> {
                            if (!method.getName().startsWith("execute"))
                                return invoke(target, method, args);
                            try (var ignored = ManagedExecutionTrace.span("sql_execute")) {
                                return invoke(target, method, args);
                            }
                        });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
