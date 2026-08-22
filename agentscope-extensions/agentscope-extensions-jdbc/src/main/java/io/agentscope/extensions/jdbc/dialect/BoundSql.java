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
package io.agentscope.extensions.jdbc.dialect;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * An assembled SQL statement with its bind parameters.
 *
 * <p>The unified return type for all dialect business-SQL methods. Consumers
 * execute {@link #sql()} and bind {@link #params()} in order.
 *
 * @param sql    the SQL statement with {@code ?} placeholders
 * @param params bind parameters in placeholder order
 * @author shanhongyu
 */
public record BoundSql(String sql, List<Object> params) {

    /** Convenience constructor accepting varargs params. */
    public BoundSql(String sql, Object... params) {
        this(sql, Collections.unmodifiableList(Arrays.asList(params)));
    }
}
