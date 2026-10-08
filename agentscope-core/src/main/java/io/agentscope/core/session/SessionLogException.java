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
package io.agentscope.core.session;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Persistence failures must propagate; they must never become successful model/tool results. */
public class SessionLogException extends RuntimeException {
    public SessionLogException(String message) {
        super(message);
    }

    public SessionLogException(String message, Throwable cause) {
        super(message, cause);
    }

    public static boolean causedBy(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable e = error; e != null && seen.add(e); e = e.getCause())
            if (e instanceof SessionLogException) return true;
        return false;
    }
}
