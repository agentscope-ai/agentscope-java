import { CopilotKit } from "@copilotkit/react-core/v2";
import React from "react";
import ReactDOM from "react-dom/client";

import { workbenchA2uiTheme, workbenchCatalog } from "@/a2ui/workbenchCatalog";
import { WORKBENCH_AGENT_ID } from "@/copilot/constants";

import App from "./App";
import "./styles.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <CopilotKit
      runtimeUrl="/agui/run"
      debug={true}
      useSingleEndpoint={false}
      agent={WORKBENCH_AGENT_ID}
      enableInspector={import.meta.env.DEV}
      headers={{}}
      // Passing a catalog is what activates the A2UI renderer against a self-hosted AG-UI
      // backend: CopilotKit normally learns about A2UI from a CopilotRuntime /info response,
      // but an explicit catalog turns it on directly.
      a2ui={{ theme: workbenchA2uiTheme, catalog: workbenchCatalog }}
    >
      <App />
    </CopilotKit>
  </React.StrictMode>,
);
