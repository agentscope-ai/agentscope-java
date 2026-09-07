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
