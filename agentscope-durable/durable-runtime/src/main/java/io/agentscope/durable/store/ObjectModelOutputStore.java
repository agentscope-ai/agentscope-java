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
package io.agentscope.durable.store;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.ModelExecutions.Batch;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Output batches backed by immutable objects, including the embedded RecordStore implementation. */
public final class ObjectModelOutputStore implements ModelOutputStore {
    private final ObjectStore objects;

    public ObjectModelOutputStore(ObjectStore objects) {
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    @Override
    public JsonNode write(Batch batch) {
        var bytes = DurableJson.value(batch).toString().getBytes(StandardCharsets.UTF_8);
        return DurableJson.value(
                objects.put(DurableJson.hash(batch.key().thread().session()), bytes));
    }

    @Override
    public Batch read(JsonNode locator) {
        var reference = DurableJson.convert(locator, ObjectStore.Reference.class);
        return DurableJson.convert(
                DurableJson.parse(new String(objects.read(reference), StandardCharsets.UTF_8)),
                Batch.class);
    }
}
