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
package io.agentscope.core.plan.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.plan.model.Plan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryPlanStorageTest {

    private InMemoryPlanStorage storage;

    @BeforeEach
    void setUp() {
        storage = new InMemoryPlanStorage();
    }

    @Test
    void testAddPlan() {
        Plan plan = new Plan("TestPlan", "Description", "Expected", new ArrayList<>());

        storage.addPlan(plan).block();

        Plan retrieved = storage.getPlan(plan.getId()).block();
        assertNotNull(retrieved);
        assertEquals(plan.getId(), retrieved.getId());
        assertEquals("TestPlan", retrieved.getName());
    }

    @Test
    void testGetPlan_NotFound() {
        Plan retrieved = storage.getPlan("non-existent-id").block();

        assertNull(retrieved);
    }

    @Test
    void testGetPlans_Empty() {
        List<Plan> plans = storage.getPlans().block();

        assertNotNull(plans);
        assertTrue(plans.isEmpty());
    }

    @Test
    void testGetPlans_Multiple() {
        Plan plan1 = new Plan("Plan1", "Desc1", "Expected1", new ArrayList<>());
        Plan plan2 = new Plan("Plan2", "Desc2", "Expected2", new ArrayList<>());

        storage.addPlan(plan1).block();
        storage.addPlan(plan2).block();

        List<Plan> plans = storage.getPlans().block();

        assertNotNull(plans);
        assertEquals(2, plans.size());
        assertTrue(plans.stream().anyMatch(p -> p.getId().equals(plan1.getId())));
        assertTrue(plans.stream().anyMatch(p -> p.getId().equals(plan2.getId())));
    }

    @Test
    void testAddPlan_Replace() {
        Plan plan1 = new Plan("Plan1", "Desc1", "Expected1", new ArrayList<>());
        plan1.setId("same-id");
        storage.addPlan(plan1).block();

        Plan plan2 = new Plan("Plan2", "Desc2", "Expected2", new ArrayList<>());
        plan2.setId("same-id");
        storage.addPlan(plan2).block();

        List<Plan> plans = storage.getPlans().block();
        assertEquals(1, plans.size());
        assertEquals("Plan2", plans.get(0).getName());
    }

    @Test
    void testConcurrentAddAndGet() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(
                    () -> {
                        try {
                            Plan plan =
                                    new Plan(
                                            "Plan" + index,
                                            "Desc" + index,
                                            "Expected" + index,
                                            new ArrayList<>());
                            storage.addPlan(plan).block();
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        latch.await();
        executor.shutdown();

        List<Plan> plans = storage.getPlans().block();
        assertEquals(threadCount, plans.size());
    }

    @Test
    void testConcurrentGetSamePlan() throws InterruptedException {
        Plan plan = new Plan("TestPlan", "Desc", "Expected", new ArrayList<>());
        storage.addPlan(plan).block();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Plan> results = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(
                    () -> {
                        try {
                            Plan retrieved = storage.getPlan(plan.getId()).block();
                            synchronized (results) {
                                results.add(retrieved);
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threadCount, results.size());
        assertTrue(results.stream().allMatch(p -> p != null && p.getId().equals(plan.getId())));
    }
}
