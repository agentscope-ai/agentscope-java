# Continuous POC walkthrough

[中文](README.md)

The examples support Bash and zsh. Read and execute the pages in this order. The cluster uses the `local-developer` development identity; no login or token is required.

| Order | Page | Action |
| --- | --- | --- |
| 1 | [API quickstart](api-quickstart.en.md) | Configure variables and the request helper once, create an Agent/Session and complete two exchanges |
| 2 | [Events and streaming](events-and-streaming.en.md) | Reuse the same Session for SSE observation, history, messages, interruption and redirection |
| 3 | [Tool calls and in-flight interaction](tool-collaboration.en.md) | Reuse configuration, create a tool Session and test Brain/Hands, MCP, input, queuing, interruption and redirection |
| 4 | [API reference](api-reference.en.md) | Keep the existing context and consult endpoint contracts and the complete route catalog as needed |

## Configure once and reuse throughout

Initialize the address, scope and base request helper only in the quickstart. Subsequent pages reuse that context; the tools page creates a separate tool-enabled test Session:

| Variable / helper | Purpose |
| --- | --- |
| `BASE_URL` | Cluster Gateway URL |
| `TENANT`, `NAMESPACE` | Request scope |
| `ENVIRONMENT_ID` | Selected environment; the quickstart defaults to the cluster’s text-verification Environment |
| `AGENT_ID`, `SESSION_ID` | Agent and Session created in the quickstart |
| `RUN_ID` | Request-key prefix for this walkthrough; retain it throughout |
| `D` | Shared temporary directory; the events page uses `events/` and the tools page uses `tools/` |
| `api` | Shared cURL request helper |

Continue in the quickstart’s terminals A and B. For a new terminal, run the complete `source /actual/path/client.sh` command printed in quickstart step 3 to restore every variable and the helper. Keep the same address and quickstart Session. The tools page creates its own test Session in step 1 and prints a dedicated context-loading command; the original `SESSION_ID` remains unchanged.

The quickstart, events and tools pages use distinct idempotency-key suffixes so their requests do not share a key. Before sending each ordinary message, wait for the preceding answer and idle. Retain the original body and key when retrying the same request.
