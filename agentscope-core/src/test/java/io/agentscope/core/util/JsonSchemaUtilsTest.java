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

package io.agentscope.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class JsonSchemaUtilsTest {

    private static final int CONCURRENT_THREAD_COUNT = 12;

    private static final int CONCURRENT_CALL_COUNT = 240;

    static class SimpleModel {
        public String name;
        public int age;
    }

    static class NestedModel {
        public String title;
        public SimpleModel author;
        public List<String> tags;
    }

    /** Holder for the type shapes a method signature can declare but no class does. */
    static class GenericHolder<T> {
        public T[] values;

        public <R> R identity(R input) {
            return input;
        }
    }

    @Test
    void testGenerateSchemaFromClassSimple() {
        Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromClass(SimpleModel.class);

        assertNotNull(schema);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("name"));
        assertTrue(properties.containsKey("age"));

        @SuppressWarnings("unchecked")
        Map<String, Object> nameProperty = (Map<String, Object>) properties.get("name");
        assertEquals("string", nameProperty.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> ageProperty = (Map<String, Object>) properties.get("age");
        assertEquals("integer", ageProperty.get("type"));
    }

    @Test
    void testGenerateSchemaFromClassNested() {
        Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromClass(NestedModel.class);

        assertNotNull(schema);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);

        assertTrue(properties.containsKey("author"));
        assertTrue(properties.containsKey("tags"));

        @SuppressWarnings("unchecked")
        Map<String, Object> authorProperty = (Map<String, Object>) properties.get("author");
        assertNotNull(authorProperty);

        @SuppressWarnings("unchecked")
        Map<String, Object> tagsProperty = (Map<String, Object>) properties.get("tags");
        assertNotNull(tagsProperty);
        assertEquals("array", tagsProperty.get("type"));
    }

    @Test
    void testConvertToObjectSimple() {
        Map<String, Object> data = Map.of("name", "Alice", "age", 30);

        SimpleModel result = JsonSchemaUtils.convertToObject(data, SimpleModel.class);

        assertNotNull(result);
        assertEquals("Alice", result.name);
        assertEquals(30, result.age);
    }

    @Test
    void testConvertToObjectNested() {
        Map<String, Object> authorData = Map.of("name", "Bob", "age", 25);
        Map<String, Object> data =
                Map.of(
                        "title",
                        "Test Article",
                        "author",
                        authorData,
                        "tags",
                        List.of("java", "test"));

        NestedModel result = JsonSchemaUtils.convertToObject(data, NestedModel.class);

        assertNotNull(result);
        assertEquals("Test Article", result.title);
        assertNotNull(result.author);
        assertEquals("Bob", result.author.name);
        assertEquals(25, result.author.age);
        assertNotNull(result.tags);
        assertEquals(2, result.tags.size());
        assertTrue(result.tags.contains("java"));
        assertTrue(result.tags.contains("test"));
    }

    @Test
    void testConvertToObjectNull() {
        assertThrows(
                IllegalStateException.class,
                () -> JsonSchemaUtils.convertToObject(null, SimpleModel.class));
    }

    @Test
    void testConvertToObjectInvalidData() {
        Map<String, Object> invalidData = Map.of("name", "Alice", "age", "not-a-number");

        assertThrows(
                RuntimeException.class,
                () -> JsonSchemaUtils.convertToObject(invalidData, SimpleModel.class));
    }

    @Test
    void testGenerateSchemaFromType() {
        // Test List<String>
        Type listType = new TypeReference<List<String>>() {}.getType();
        Map<String, Object> listSchema = JsonSchemaUtils.generateSchemaFromType(listType);
        assertNotNull(listSchema);
        assertEquals("array", listSchema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) listSchema.get("items");
        assertNotNull(items);
        assertEquals("string", items.get("type"));

        // Test Map<String, Integer>
        Type mapType = new TypeReference<Map<String, Integer>>() {}.getType();
        Map<String, Object> mapSchema = JsonSchemaUtils.generateSchemaFromType(mapType);
        assertNotNull(mapSchema);
        assertEquals("object", mapSchema.get("type"));
    }

    @Test
    void testGenerateSchemaFromClassRepeatedCallsReturnEqualIndependentMaps() {
        Map<String, Object> first = JsonSchemaUtils.generateSchemaFromClass(SimpleModel.class);
        Map<String, Object> second = JsonSchemaUtils.generateSchemaFromClass(SimpleModel.class);

        // A repeated class must yield an equal schema, so caching cannot change the result.
        assertEquals(first, second);

        // Each call must return a fresh, independently mutable map: mutating one must not leak
        // into another, matching in-place-mutating callers such as ToolSchemaGenerator.
        first.put("description", "mutated");
        assertFalse(second.containsKey("description"));

        Map<String, Object> third = JsonSchemaUtils.generateSchemaFromClass(SimpleModel.class);
        assertFalse(third.containsKey("description"));
        assertEquals(second, third);
    }

    @Test
    void testGenerateSchemaFromTypeRepeatedCallsReturnEqualIndependentMaps() {
        Type listType = new TypeReference<List<String>>() {}.getType();

        Map<String, Object> first = JsonSchemaUtils.generateSchemaFromType(listType);
        Map<String, Object> second = JsonSchemaUtils.generateSchemaFromType(listType);

        assertEquals(first, second);

        first.put("description", "mutated");
        assertFalse(second.containsKey("description"));

        Map<String, Object> third = JsonSchemaUtils.generateSchemaFromType(listType);
        assertFalse(third.containsKey("description"));
        assertEquals(second, third);
    }

    @Test
    void testGenerateSchemaFromClassNestedMutationDoesNotAffectLaterCalls() {
        Map<String, Object> first = JsonSchemaUtils.generateSchemaFromClass(NestedModel.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> firstProperties = (Map<String, Object>) first.get("properties");
        assertNotNull(firstProperties);

        // Real callers mutate below the top level: ToolSchemaGenerator hoists "$defs" out of
        // nested schemas and ReActAgent rewrites nested properties in place. A later call must
        // still observe the pristine schema, which is exactly the deep-copy invariant the cache
        // relies on.
        assertNotNull(firstProperties.remove("tags"));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstAuthor = (Map<String, Object>) firstProperties.get("author");
        assertNotNull(firstAuthor);
        firstAuthor.put("description", "mutated");

        Map<String, Object> second = JsonSchemaUtils.generateSchemaFromClass(NestedModel.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> secondProperties = (Map<String, Object>) second.get("properties");
        assertNotNull(secondProperties);
        assertTrue(secondProperties.containsKey("tags"));

        @SuppressWarnings("unchecked")
        Map<String, Object> secondAuthor = (Map<String, Object>) secondProperties.get("author");
        assertNotNull(secondAuthor);
        assertFalse(secondAuthor.containsKey("description"));
    }

    @Test
    void testGenerateSchemaFromTypeNestedMutationDoesNotAffectLaterCalls() {
        Type listType = new TypeReference<List<SimpleModel>>() {}.getType();

        Map<String, Object> first = JsonSchemaUtils.generateSchemaFromType(listType);

        @SuppressWarnings("unchecked")
        Map<String, Object> firstItems = (Map<String, Object>) first.get("items");
        assertNotNull(firstItems);
        firstItems.put("description", "mutated");

        Map<String, Object> second = JsonSchemaUtils.generateSchemaFromType(listType);

        @SuppressWarnings("unchecked")
        Map<String, Object> secondItems = (Map<String, Object>) second.get("items");
        assertNotNull(secondItems);
        assertFalse(secondItems.containsKey("description"));
    }

    @Test
    void testGenerateSchemaFromClassNullThrows() {
        // Caching routes a null class through ClassValue#get, which rejects null keys; the
        // resulting NPE must match the pre-cache behavior for a null argument.
        assertThrows(
                NullPointerException.class, () -> JsonSchemaUtils.generateSchemaFromClass(null));
    }

    @Test
    void testGenerateSchemaFromTypeNullThrows() {
        // The public entry point rejects null before it reaches the cache, matching the pre-cache
        // behavior for a null argument.
        assertThrows(
                NullPointerException.class, () -> JsonSchemaUtils.generateSchemaFromType(null));
    }

    @Test
    void testGenerateSchemaFromTypeVariableIsCachedUnderItsDeclaringClass() {
        // A type variable has no raw class, so it is cached under the class that declares it. Both
        // calls must agree, whether the entry is served from the cache or generated again.
        Type typeVariable = List.class.getTypeParameters()[0];

        Map<String, Object> first = JsonSchemaUtils.generateSchemaFromType(typeVariable);
        Map<String, Object> second = JsonSchemaUtils.generateSchemaFromType(typeVariable);

        assertNotNull(first);
        assertEquals(first, second);
    }

    @Test
    void testGenerateSchemaFromMethodTypeVariableIsCachedUnderItsDeclaringClass() throws Exception {
        // A type variable declared on a method has no raw class either, and its declaration is the
        // method rather than a class, so it is cached under the method's declaring class.
        Type typeVariable =
                GenericHolder.class.getMethod("identity", Object.class).getTypeParameters()[0];

        Map<String, Object> first = JsonSchemaUtils.generateSchemaFromType(typeVariable);
        Map<String, Object> second = JsonSchemaUtils.generateSchemaFromType(typeVariable);

        assertNotNull(first);
        assertEquals(first, second);
    }

    @Test
    void testGenerateSchemaFromGenericArrayIsCachedUnderItsComponentClass() throws Exception {
        // A generic array carries no raw class of its own; it resolves through its component type.
        Type genericArray = GenericHolder.class.getField("values").getGenericType();

        Map<String, Object> first = JsonSchemaUtils.generateSchemaFromType(genericArray);
        Map<String, Object> second = JsonSchemaUtils.generateSchemaFromType(genericArray);

        assertNotNull(first);
        assertEquals(first, second);
    }

    @Test
    void testGenerateSchemaFromWildcardIsGeneratedWithoutCaching() {
        // A wildcard is only ever a type argument, never a parameter or return type, so a
        // reflective signature cannot produce one at the top level. Only a caller synthesizing one
        // reaches the uncached path; both calls must still agree.
        Type wildcard =
                ((ParameterizedType) new TypeReference<List<?>>() {}.getType())
                        .getActualTypeArguments()[0];

        Map<String, Object> first = JsonSchemaUtils.generateSchemaFromType(wildcard);
        Map<String, Object> second = JsonSchemaUtils.generateSchemaFromType(wildcard);

        assertEquals(first, second);
    }

    @Test
    void testGenerateSchemaFromParameterizedTypeIsHeldOnItsApplicationClass() throws Exception {
        // List<TenantElement> mentions java.util.List, which is never unloaded, and TenantElement.
        // The entry has to live on the application class: parked on List, it would keep the element
        // class, and the classloader that defined it, reachable for the lifetime of the JVM.
        Type parameterized = new TypeReference<List<TenantElement>>() {}.getType();

        JsonSchemaUtils.generateSchemaFromType(parameterized);

        assertTrue(typeSlot(TenantElement.class).containsKey(parameterized));
        assertFalse(typeSlot(List.class).containsKey(parameterized));
    }

    @Test
    void testGenerateSchemaFromBoundedWildcardIsHeldOnItsBoundClass() throws Exception {
        // A wildcard is only ever a type argument, so the bound it declares decides which class the
        // signature mentioning it belongs to.
        Type boundedWildcard = new TypeReference<List<? extends TenantElement>>() {}.getType();

        JsonSchemaUtils.generateSchemaFromType(boundedWildcard);

        assertTrue(typeSlot(TenantElement.class).containsKey(boundedWildcard));
        assertFalse(typeSlot(List.class).containsKey(boundedWildcard));
    }

    @Test
    void testGenerateSchemaFromConstructorTypeVariableIsHeldOnItsDeclaringClass() throws Exception {
        // A constructor declares type variables just as a method does, and it is a
        // GenericDeclaration without being a Method. Matching only Method would send this type to
        // the uncached path, which takes the global lock on every call.
        Type typeVariable =
                GenericConstructorHolder.class.getConstructor(Object.class).getTypeParameters()[0];

        JsonSchemaUtils.generateSchemaFromType(typeVariable);

        assertTrue(typeSlot(GenericConstructorHolder.class).containsKey(typeVariable));
    }

    @Test
    void testGenerateSchemaFromBareClassIsHeldOnTheClassSlotOnly() throws Exception {
        // Both entry points describe the same class, so they must share one slot rather than
        // generating and storing the same schema twice.
        JsonSchemaUtils.generateSchemaFromType(BareClassFixture.class);

        assertNotNull(classSlot(BareClassFixture.class));
        assertFalse(typeSlot(BareClassFixture.class).containsKey(BareClassFixture.class));
    }

    /**
     * Reads the private type slot of a class. Asserting the slot directly is the only way to check
     * which class a type is cached under: every schema the public API returns is a fresh copy, so
     * the entry it came from is invisible in behaviour.
     */
    @SuppressWarnings("unchecked")
    private static Map<Type, JsonNode> typeSlot(Class<?> scopeClass) throws Exception {
        Field slot = JsonSchemaUtils.class.getDeclaredField("TYPE_SCHEMA_SLOT");
        slot.setAccessible(true);
        ClassValue<Map<Type, JsonNode>> slots = (ClassValue<Map<Type, JsonNode>>) slot.get(null);
        return slots.get(scopeClass);
    }

    /** Reads the private class slot of a class. */
    @SuppressWarnings("unchecked")
    private static JsonNode classSlot(Class<?> clazz) throws Exception {
        Field slot = JsonSchemaUtils.class.getDeclaredField("CLASS_SCHEMA_SLOT");
        slot.setAccessible(true);
        ClassValue<AtomicReference<JsonNode>> slots =
                (ClassValue<AtomicReference<JsonNode>>) slot.get(null);
        return slots.get(clazz).get();
    }

    /** Element class of the signatures used to check which class a type is cached under. */
    static class TenantElement {
        public String name;
    }

    /** Declares a type variable on a constructor, which is a GenericDeclaration but not a Method. */
    static class GenericConstructorHolder {
        public <T> GenericConstructorHolder(T value) {}
    }

    /** Reached as a bare class through {@code generateSchemaFromType}. */
    static class BareClassFixture {
        public String label;
    }

    static class ConcurrentClassA {
        public String name;
        public int age;
    }

    static class ConcurrentClassB {
        public String title;
        public List<String> tags;
    }

    static class ConcurrentClassC {
        public String id;
        public boolean active;
    }

    static class ConcurrentClassD {
        public double score;
    }

    @Test
    void testGenerateSchemaFromClassConcurrently() throws Exception {
        List<Class<?>> targetClasses = List.of(ConcurrentClassA.class, ConcurrentClassB.class);

        List<Map<String, Object>> schemas =
                generateConcurrently(
                        index ->
                                JsonSchemaUtils.generateSchemaFromClass(
                                        targetClasses.get(index % targetClasses.size())));

        assertEquals(CONCURRENT_CALL_COUNT, schemas.size());
        for (Map<String, Object> schema : schemas) {
            assertNotNull(schema);
            assertEquals("object", schema.get("type"));
            assertNotNull(schema.get("properties"));
        }
    }

    @Test
    void testGenerateSchemaFromTypeConcurrently() throws Exception {
        // The first two are variants of the same raw class and mention no application class, so
        // both are held on java.util.List and share its slot map: they exercise the structure the
        // cache's correctness rests on. The last two are held on the class each one mentions, so
        // the scoped slots are stressed as well.
        Type listOfString = new TypeReference<List<String>>() {}.getType();
        Type listOfInteger = new TypeReference<List<Integer>>() {}.getType();
        Type listOfC = new TypeReference<List<ConcurrentClassC>>() {}.getType();
        Type listOfD = new TypeReference<List<ConcurrentClassD>>() {}.getType();
        List<Type> targetTypes = List.of(listOfString, listOfInteger, listOfC, listOfD);

        List<Map<String, Object>> schemas =
                generateConcurrently(
                        index ->
                                JsonSchemaUtils.generateSchemaFromType(
                                        targetTypes.get(index % targetTypes.size())));

        assertEquals(CONCURRENT_CALL_COUNT, schemas.size());
        for (Map<String, Object> schema : schemas) {
            assertNotNull(schema);
            assertEquals("array", schema.get("type"));
        }

        // Variants sharing one slot must stay independent of each other.
        assertTrue(typeSlot(List.class).containsKey(listOfString));
        assertTrue(typeSlot(List.class).containsKey(listOfInteger));
        assertNotEquals(
                JsonSchemaUtils.generateSchemaFromType(listOfString),
                JsonSchemaUtils.generateSchemaFromType(listOfInteger));
        assertNotEquals(
                JsonSchemaUtils.generateSchemaFromType(listOfC),
                JsonSchemaUtils.generateSchemaFromType(listOfD));
    }

    /**
     * Runs the given generator on a fixed thread pool, with all tasks released at the same
     * time to maximize the chance of overlapping schema generation. Any exception thrown
     * inside a task propagates through {@code Future#get} and fails the test.
     */
    private static List<Map<String, Object>> generateConcurrently(
            IntFunction<Map<String, Object>> generator) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_CALL_COUNT; i++) {
                final int index = i;
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return generator.apply(index);
                                }));
            }
            start.countDown();
            List<Map<String, Object>> results = new ArrayList<>();
            for (Future<Map<String, Object>> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
