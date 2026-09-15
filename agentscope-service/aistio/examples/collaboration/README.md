# Issue collaboration example

This example creates a persistent Team, assigns an Issue to its leader, adds a structured worker mention, and inspects the resulting routes and AgentTasks.

```bash
export AISTIO_URL=http://localhost:8080
export AGENTSCOPE_API_TOKEN=...
./run.sh
```

Set `LEADER_AGENT` and `WORKER_AGENT` to registered Agent references. The script prints IDs only; Agents receive the actual obligation through the configured Managed, External, or Hosted binding.
