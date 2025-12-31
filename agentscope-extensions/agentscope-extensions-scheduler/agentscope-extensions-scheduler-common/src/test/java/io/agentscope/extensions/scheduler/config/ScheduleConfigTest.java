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
package io.agentscope.extensions.scheduler.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ScheduleConfig}. */
class ScheduleConfigTest {

    @Test
    void testBuilderWithNoneMode() {
        ScheduleConfig config = ScheduleConfig.builder().build();

        assertNotNull(config);
        assertEquals(ScheduleMode.NONE, config.getScheduleMode());
    }

    @Test
    void testBuilderWithCronMode() {
        ScheduleConfig config = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        assertNotNull(config);
        assertEquals(ScheduleMode.CRON, config.getScheduleMode());
        assertEquals("0 0 8 * * ?", config.getCronExpression());
    }

    @Test
    void testBuilderWithFixedRateMode() {
        ScheduleConfig config = ScheduleConfig.builder().fixedRate(5000L).build();

        assertNotNull(config);
        assertEquals(ScheduleMode.FIXED_RATE, config.getScheduleMode());
        assertEquals(5000L, config.getFixedRate());
    }

    @Test
    void testBuilderWithFixedDelayMode() {
        ScheduleConfig config = ScheduleConfig.builder().fixedDelay(3000L).build();

        assertNotNull(config);
        assertEquals(ScheduleMode.FIXED_DELAY, config.getScheduleMode());
        assertEquals(3000L, config.getFixedDelay());
    }

    @Test
    void testGetScheduleMode() {
        ScheduleConfig config = ScheduleConfig.builder().cron("0 0 * * * ?").build();

        assertEquals(ScheduleMode.CRON, config.getScheduleMode());
    }

    @Test
    void testGetCronExpression() {
        ScheduleConfig config = ScheduleConfig.builder().cron("0 0 9 * * MON-FRI").build();

        assertEquals("0 0 9 * * MON-FRI", config.getCronExpression());
    }

    @Test
    void testGetFixedRate() {
        ScheduleConfig config = ScheduleConfig.builder().fixedRate(10000L).build();

        assertEquals(10000L, config.getFixedRate());
    }

    @Test
    void testGetFixedDelay() {
        ScheduleConfig config = ScheduleConfig.builder().fixedDelay(7000L).build();

        assertEquals(7000L, config.getFixedDelay());
    }

    @Test
    void testGetInitialDelay() {
        ScheduleConfig config =
                ScheduleConfig.builder().fixedRate(5000L).initialDelay(1000L).build();

        assertEquals(1000L, config.getInitialDelay());
    }

    @Test
    void testGetZoneId() {
        ScheduleConfig config =
                ScheduleConfig.builder().cron("0 0 8 * * ?").zoneId("Asia/Shanghai").build();

        assertEquals("Asia/Shanghai", config.getZoneId());
    }

    @Test
    void testValidationCronModeWithNullExpression() {
        assertThrows(
                IllegalArgumentException.class, () -> ScheduleConfig.builder().cron(null).build());
    }

    @Test
    void testValidationCronModeWithEmptyExpression() {
        assertThrows(
                IllegalArgumentException.class, () -> ScheduleConfig.builder().cron("").build());
    }

    @Test
    void testValidationFixedRateModeWithNullRate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ScheduleConfig.builder().fixedRate(null).build());
    }

    @Test
    void testValidationFixedRateModeWithZeroRate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ScheduleConfig.builder().fixedRate(0L).build());
    }

    @Test
    void testValidationFixedRateModeWithNegativeRate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ScheduleConfig.builder().fixedRate(-1L).build());
    }

    @Test
    void testValidationFixedDelayModeWithNullDelay() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ScheduleConfig.builder().fixedDelay(null).build());
    }

    @Test
    void testValidationFixedDelayModeWithZeroDelay() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ScheduleConfig.builder().fixedDelay(0L).build());
    }

    @Test
    void testValidationFixedDelayModeWithNegativeDelay() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ScheduleConfig.builder().fixedDelay(-1L).build());
    }

    @Test
    void testNoneModeDoesNotRequireOtherFields() {
        ScheduleConfig config = ScheduleConfig.builder().build();

        assertNotNull(config);
        assertEquals(ScheduleMode.NONE, config.getScheduleMode());
        assertNull(config.getCronExpression());
        assertNull(config.getFixedRate());
        assertNull(config.getFixedDelay());
    }
}
