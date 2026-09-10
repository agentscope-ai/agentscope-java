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

/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.builder.control;

import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.service.SessionEventMirror;
import org.springframework.stereotype.Component;

/** Projects data-plane session events into the aistiod runtime event store. */
@Component
public class ControlPlaneSessionEventMirror implements SessionEventMirror {

    private final ControlPlaneClient controlPlaneClient;

    public ControlPlaneSessionEventMirror(ControlPlaneClient controlPlaneClient) {
        this.controlPlaneClient = controlPlaneClient;
    }

    @Override
    public void mirror(SessionEventDto event) {
        controlPlaneClient.appendSessionEvent(event);
    }
}
