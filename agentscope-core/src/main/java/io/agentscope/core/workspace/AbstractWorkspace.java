/*
 * Copyright 2026-2027 the original author or authors.
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

package io.agentscope.core.workspace;

import java.util.UUID;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 */

public abstract class AbstractWorkspace implements Workspace {

    private static final String DEFAULT_HOME_DIR = "/workspace";

    @Override
    public String workspaceRoot() {

        return DEFAULT_HOME_DIR;
    }

    @Override
    public String sandboxId() {

        return uuid();
    }

    private static String uuid() {

        return UUID.randomUUID().toString().replace("-", "");
    }
}
