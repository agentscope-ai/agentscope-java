import { PiAdapter, PiDurableAdapter } from "@managed-agents/adapter-pi";
import { runWorker } from "@managed-agents/harness-runtime/worker";
const selected = process.env.MANAGED_AGENTS_HARNESS_TYPE ?? "pi";
if (selected !== "pi" && selected !== "pi-durable")
  throw new Error("PI Brain requires pi or pi-durable");
await runWorker(
  selected === "pi-durable" ? new PiDurableAdapter() : new PiAdapter(),
);
