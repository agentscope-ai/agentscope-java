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
package io.agentscope.spring.boot.admin.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdminCommandRegistryTest {

    private static AdminCommand cmd(String id, String title) {
        return new AdminCommand(
                id, title, "Test", CommandPlane.DATA, "GET", "/" + id, List.of(), false, true, "");
    }

    @Test
    void registerAndFind() {
        AdminCommandRegistry r = new AdminCommandRegistry();
        AdminCommand c = cmd("a.b", "A B");
        r.register(c);
        assertThat(r.find("a.b")).contains(c);
        assertThat(r.size()).isEqualTo(1);
    }

    @Test
    void duplicateRegistrationIsIdempotent() {
        AdminCommandRegistry r = new AdminCommandRegistry();
        AdminCommand c = cmd("a.b", "A B");
        r.register(c);
        r.register(c);
        assertThat(r.size()).isEqualTo(1);
    }

    @Test
    void collidingIdsRejected() {
        AdminCommandRegistry r = new AdminCommandRegistry();
        r.register(cmd("a.b", "A B"));
        assertThatThrownBy(() -> r.register(cmd("a.b", "Different")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id collision");
    }

    @Test
    void listIsSortedByCategoryThenId() {
        AdminCommandRegistry r = new AdminCommandRegistry();
        AdminCommand sys =
                new AdminCommand(
                        "z.last",
                        "Z",
                        "System",
                        CommandPlane.CONTROL,
                        "GET",
                        "/z",
                        List.of(),
                        false,
                        true,
                        "");
        AdminCommand sess1 =
                new AdminCommand(
                        "a.compact",
                        "A",
                        "Session",
                        CommandPlane.DATA,
                        "POST",
                        "/a",
                        List.of(),
                        true,
                        false,
                        "");
        AdminCommand sess0 =
                new AdminCommand(
                        "a.abort",
                        "B",
                        "Session",
                        CommandPlane.DATA,
                        "POST",
                        "/b",
                        List.of(),
                        true,
                        true,
                        "");
        r.register(sys);
        r.register(sess1);
        r.register(sess0);

        List<AdminCommand> list = r.list();
        assertThat(list)
                .extracting(AdminCommand::id)
                .containsExactly("a.abort", "a.compact", "z.last");
    }
}
