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
package io.agentscope.core.agui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.HarnessAgent;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

class AguiUtilTest {

    @Test
    void constructorIsPackagePrivateUtility() throws Exception {
        Constructor<AguiUtil> constructor = AguiUtil.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }

    @Test
    void isHarnessAgentDetectsHarnessTypeAndIgnoresOthers() {
        assertTrue(AguiUtil.isHarnessAgent(new HarnessAgent()));
        assertTrue(AguiUtil.isHarnessAgent(new DelegatingAgent(mock(ReActAgent.class))));
        assertFalse(AguiUtil.isHarnessAgent(mock(Agent.class)));
        assertFalse(AguiUtil.isHarnessAgent(mock(ReActAgent.class)));
        assertFalse(AguiUtil.isHarnessAgent(null));
    }

    @Test
    void asReActAgentReturnsDirectInstanceWithoutClosing() {
        ReActAgent agent = mock(ReActAgent.class);

        assertSame(agent, AguiUtil.asReActAgent(agent));
        verify(agent, never()).close();
    }

    @Test
    void asReActAgentUnwrapsPublicDelegate() {
        ReActAgent delegate = mock(ReActAgent.class);
        Agent wrapper = new DelegatingAgent(delegate);

        assertSame(delegate, AguiUtil.asReActAgent(wrapper));
        verify(delegate, never()).close();
    }

    @Test
    void asReActAgentReturnsNullWhenDelegateIsMissing() {
        assertNull(AguiUtil.asReActAgent(mock(Agent.class)));
        assertNull(AguiUtil.asReActAgent(null));
        assertNull(AguiUtil.asReActAgent(new HarnessAgent()));
        assertNull(AguiUtil.asReActAgent(new NonReActDelegateAgent()));
    }

    private static final class DelegatingAgent extends HarnessAgent {
        private final ReActAgent delegate;

        private DelegatingAgent(ReActAgent delegate) {
            this.delegate = delegate;
        }

        public ReActAgent getDelegate() {
            return delegate;
        }
    }

    private static final class NonReActDelegateAgent extends HarnessAgent {
        public Object getDelegate() {
            return "not-a-react-agent";
        }
    }
}
