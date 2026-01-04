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

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ScheduleMode}. */
class ScheduleModeTest {

    @Test
    void testNoneMode() {
        ScheduleMode mode = ScheduleMode.NONE;
        assertNotNull(mode);
        assertEquals("NONE", mode.name());
    }

    @Test
    void testCronMode() {
        ScheduleMode mode = ScheduleMode.CRON;
        assertNotNull(mode);
        assertEquals("CRON", mode.name());
    }

    @Test
    void testFixedRateMode() {
        ScheduleMode mode = ScheduleMode.FIXED_RATE;
        assertNotNull(mode);
        assertEquals("FIXED_RATE", mode.name());
    }

    @Test
    void testFixedDelayMode() {
        ScheduleMode mode = ScheduleMode.FIXED_DELAY;
        assertNotNull(mode);
        assertEquals("FIXED_DELAY", mode.name());
    }

    @Test
    void testValuesMethod() {
        ScheduleMode[] modes = ScheduleMode.values();
        assertNotNull(modes);
        assertEquals(4, modes.length);
    }

    @Test
    void testValueOfMethod() {
        ScheduleMode mode = ScheduleMode.valueOf("CRON");
        assertNotNull(mode);
        assertEquals(ScheduleMode.CRON, mode);
    }
}
