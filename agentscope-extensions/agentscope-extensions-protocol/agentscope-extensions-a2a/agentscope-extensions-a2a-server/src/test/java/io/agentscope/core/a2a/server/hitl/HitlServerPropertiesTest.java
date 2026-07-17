/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.core.a2a.server.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HitlServerPropertiesTest {

    @Test
    void hitlIsDisabledByDefaultWithFiniteLocalTtls() {
        HitlServerProperties properties = HitlServerProperties.builder().build();

        assertFalse(properties.enabled());
        assertEquals(HitlServerProperties.Durability.LOCAL, properties.durability());
        assertEquals(Duration.ofDays(30), properties.taskTtl());
        assertEquals(Duration.ofDays(7), properties.handoffTtl());
        assertEquals(Duration.ofMinutes(1), properties.executionLeaseTtl());
    }
}
