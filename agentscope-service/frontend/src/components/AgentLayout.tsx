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

import { Navigate, useLocation, useParams } from "react-router-dom";
import { useControlPlaneScope } from "@/app/ScopeContext";
import { legacyAgentManagePath } from "@/features/build/agents/agentNavigation";

/** Compatibility entrypoint. All Agent pages share the catalog detail shell. */
export default function AgentLayout() {
  const { id = "" } = useParams();
  const location = useLocation();
  const scope = useControlPlaneScope();
  const segments = location.pathname.split("/").filter(Boolean);
  const manageIndex = segments.indexOf("manage");
  const [section = "settings", session] = segments.slice(
    manageIndex >= 0 ? manageIndex + 1 : 3,
  );
  const managedSession = new URLSearchParams(location.search).get("managed");
  if (section === "sessions" && session === "_managed" && managedSession) {
    return (
      <Navigate
        replace
        to={scope.scopedPath(
          `/managed/sessions/${encodeURIComponent(managedSession)}?tab=details`,
        )}
      />
    );
  }
  if (section === "sessions" && session) {
    return (
      <Navigate
        replace
        to={scope.scopedPath(
          `/agent-center/agents/${encodeURIComponent(id)}/sessions/${encodeURIComponent(session)}`,
        )}
      />
    );
  }
  return (
    <Navigate
      replace
      to={scope.scopedPath(legacyAgentManagePath(id, section, location.search))}
    />
  );
}
