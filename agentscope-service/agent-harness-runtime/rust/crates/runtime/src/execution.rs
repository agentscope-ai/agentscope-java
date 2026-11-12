use crate::client::{self, Transport};
use crate::kernel::{
    Checkpoint, Kernel, KernelEvent, KernelSession, ModelGate, ModelUsage, NativeCall, NativeOutput,
};
use crate::preview::TextPreview;
use crate::protocol::{
    Claim, Recovery, RunConfiguration, STATE_FORMAT, ToolOperation, ToolResult, VERSION, canonical,
    counter, framed_hash, sha256, task_id,
};
use anyhow::{Result, bail, ensure};
use async_trait::async_trait;
use chrono::DateTime;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

struct State {
    revision: u64,
    cursor: u64,
    deadline: Instant,
    released: bool,
    poisoned: bool,
    checkpoint: Option<Checkpoint>,
    operations: HashMap<String, ToolOperation>,
    applied: HashSet<String>,
    results: HashMap<String, ToolResult>,
    model_calls: u64,
    interactions: Vec<Value>,
}

pub struct Execution {
    pub client: Arc<dyn Transport>,
    pub claim: Claim,
    pub configuration: RunConfiguration,
    pub cancel: CancellationToken,
    state: Mutex<State>,
}

impl Execution {
    pub async fn load(
        client: Arc<dyn Transport>,
        claim: Claim,
        configuration: RunConfiguration,
        cancel: CancellationToken,
    ) -> Result<Arc<Self>> {
        ensure!(
            claim.scope == configuration.tool_configuration.scope,
            "configuration scope mismatch"
        );
        counter(&claim.fence.epoch)?;
        ensure!(
            configuration.inline_wait_ms.unwrap_or(1000) <= 600_000,
            "inline wait out of range"
        );
        let identity = json!({"protocolVersion":VERSION,"scope":claim.scope,"turnId":claim.turn_id,"fence":claim.fence});
        let response = client.post("recovery/read", &identity).await?;
        let recovery: Recovery = if let Some(bundle) = response.get("bundle") {
            serde_json::from_value(bundle.clone())?
        } else {
            let reference = serde_json::from_value(response["bundleRef"].clone())?;
            serde_json::from_slice(&client::read(client.as_ref(), &claim.scope, &reference).await?)?
        };
        ensure!(recovery.terminal.is_none(), "execution already terminal");
        let checkpoint = if let Some(native) = &recovery.native_state {
            ensure!(
                native.harness_type == "codex"
                    && native.state_format_version == STATE_FORMAT
                    && native.checkpoint_ref.format_version == STATE_FORMAT,
                "native checkpoint format mismatch"
            );
            let mut checkpoint = serde_json::from_slice::<Checkpoint>(
                &client::read(client.as_ref(), &claim.scope, &native.checkpoint_ref).await?,
            )?;
            if checkpoint.platform_turn_id != claim.turn_id {
                ensure!(
                    recovery.tool_operations.is_empty()
                        && recovery.model_updates.is_empty()
                        && recovery.result_applications.is_empty(),
                    "current Turn has boundaries but a different native owner"
                );
                // Session history crosses Turns, while tool operations and recovery do not.
                checkpoint.turn_id = None;
                checkpoint.platform_turn_id = claim.turn_id.clone();
                checkpoint.messages.clear();
            }
            Some(checkpoint)
        } else {
            None
        };
        let mut operations = HashMap::new();
        for op in recovery.tool_operations {
            ensure!(
                op.task_id == task_id(&claim.scope, &claim.turn_id, &op.operation_id)
                    && op.execution_id == op.task_id
                    && op.native_call_ids == [op.operation_id.clone()]
                    && op.configuration_ref.scope == claim.scope,
                "invalid recovered operation"
            );
            ensure!(
                operations.insert(op.operation_id.clone(), op).is_none(),
                "duplicate recovered operation"
            );
        }
        let mut applied = HashSet::new();
        for application in recovery.result_applications {
            let op = operations
                .get(&application.native_call_id)
                .ok_or_else(|| anyhow::anyhow!("applied call has no authorization"))?;
            ensure!(
                application.result_ids
                    == [format!(
                        "result_{}",
                        framed_hash(&[&op.task_id, &op.execution_id])
                    )],
                "result application identity mismatch"
            );
            ensure!(
                checkpoint
                    .as_ref()
                    .is_some_and(|c| c.applied_calls.contains(&application.native_call_id)),
                "applied result missing in native checkpoint"
            );
            applied.insert(application.native_call_id);
        }
        let mut results = HashMap::new();
        for result in recovery.accepted_results {
            let op = operations
                .values()
                .find(|o| o.task_id == result.task_id)
                .ok_or_else(|| anyhow::anyhow!("result without operation"))?;
            result.validate(op, &claim.scope)?;
            results.insert(result.task_id.clone(), result);
        }
        let revision = counter(&recovery.brain_revision)?;
        let cursor = recovery
            .native_state
            .as_ref()
            .map(|s| counter(&s.native_cursor))
            .transpose()?
            .unwrap_or(0);
        let instance = Arc::new(Self {
            client,
            claim,
            configuration,
            cancel,
            state: Mutex::new(State {
                revision,
                cursor,
                checkpoint,
                operations,
                applied,
                results,
                model_calls: recovery
                    .model_updates
                    .iter()
                    .filter(|v| v["phase"] == "dispatch")
                    .count() as u64,
                interactions: recovery.interaction_updates,
                deadline: Instant::now(),
                released: false,
                poisoned: false,
            }),
        });
        instance.renew().await?;
        Ok(instance)
    }

    pub fn identity(&self) -> Value {
        json!({"protocolVersion":VERSION,"scope":self.claim.scope,"turnId":self.claim.turn_id,"fence":self.claim.fence})
    }

    fn check(&self, state: &State) -> Result<()> {
        ensure!(
            !self.cancel.is_cancelled()
                && !state.released
                && !state.poisoned
                && Instant::now() < state.deadline,
            "execution ownership unavailable"
        );
        Ok(())
    }

    pub async fn renew(&self) -> Result<()> {
        let start = Instant::now();
        let mut request = self.identity();
        request["renewalId"] = Uuid::new_v4().to_string().into();
        let receipt = self.client.post("executions/renew", &request).await?;
        ensure!(
            receipt["fence"] == serde_json::to_value(&self.claim.fence)?,
            "lease fence mismatch"
        );
        let expiry = DateTime::parse_from_rfc3339(
            receipt["expiresAt"]
                .as_str()
                .ok_or_else(|| anyhow::anyhow!("missing lease expiry"))?,
        )?;
        let server = DateTime::parse_from_rfc3339(
            receipt["serverTime"]
                .as_str()
                .ok_or_else(|| anyhow::anyhow!("missing server clock"))?,
        )?;
        let duration = (expiry - server).to_std()?;
        ensure!(duration > Duration::from_secs(2), "lease too short");
        let mut state = self.state.lock().await;
        state.deadline = start + duration - Duration::from_secs(1);
        self.check(&state)
    }

    async fn boundary_locked(
        &self,
        state: &mut State,
        mut checkpoint: Checkpoint,
        mut changes: Value,
    ) -> Result<()> {
        self.check(state)?;
        // No boundary can proceed after an uncertain commit, even if a caller catches its error.
        state.poisoned = true;
        checkpoint.platform_turn_id = self.claim.turn_id.clone();
        let next_cursor = state
            .cursor
            .checked_add(1)
            .ok_or_else(|| anyhow::anyhow!("cursor overflow"))?
            .to_string();
        for field in [
            "toolCalls",
            "resultApplications",
            "inputApplications",
            "modelUpdates",
            "interactionUpdates",
        ] {
            if let Some(items) = changes.get_mut(field).and_then(Value::as_array_mut) {
                for item in items {
                    if item.get("position").is_some() {
                        item["position"] = json!({"nativeCursor":next_cursor});
                    }
                }
            }
        }
        let reference = client::put(
            self.client.as_ref(),
            &self.claim.scope,
            &serde_json::to_vec(&checkpoint)?,
            STATE_FORMAT,
        )
        .await?;
        let mut request = self.identity();
        request["commitId"] = Uuid::new_v4().to_string().into();
        request["expectedBrainRevision"] = state.revision.to_string().into();
        request["nativeState"] = json!({"harnessType":"codex","stateFormatVersion":STATE_FORMAT,"nativeCursor":next_cursor,"checkpointRef":reference});
        request["changes"] = changes;
        let previous = state
            .checkpoint
            .as_ref()
            .map(|value| value.messages.as_slice())
            .unwrap_or(&[]);
        let mut facts = Vec::new();
        let mut inline_remaining = 128 * 1024;
        for message in &checkpoint.messages {
            if let Some(prior) = previous.iter().find(|prior| prior.id == message.id) {
                ensure!(prior == message, "committed public message changed");
                continue;
            }
            let id = framed_hash(&[&self.claim.turn_id, &message.id]);
            let content = message
                .text
                .iter()
                .map(|text| json!({"type":"text","text":text}))
                .collect::<Vec<_>>();
            facts.push(json!({"factId":format!("message_{id}"),"type":"item.completed","occurredAt":chrono::Utc::now().to_rfc3339(),
                "content":{"inline":{"schema_version":1,"turn_id":self.claim.turn_id,"item_id":format!("item_{id}"),
                "item":{"id":format!("item_{id}"),"message_id":message.id,"role":"assistant","type":"message","status":"completed","content":content}}}}));
        }
        for fact in &mut facts {
            let bytes = serde_json::to_vec(&fact["content"]["inline"])?;
            if bytes.len() <= (16 * 1024).min(inline_remaining) {
                inline_remaining -= bytes.len();
            } else {
                let reference = client::put(
                    self.client.as_ref(),
                    &self.claim.scope,
                    &bytes,
                    "public-fact-v1",
                )
                .await?;
                fact["content"] = json!({"ref":reference});
            }
        }
        if !facts.is_empty() {
            request["publicFacts"] = json!(facts);
        }
        let receipt = client::commit(self.client.as_ref(), &request).await?;
        state.revision = counter(&receipt.brain_revision)?;
        state.cursor = counter(&receipt.native_cursor)?;
        state.checkpoint = Some(checkpoint);
        state.released = request["changes"].get("suspension").is_some()
            || request["changes"].get("terminal").is_some();
        state.poisoned = false;
        // A valid receipt can arrive after local ownership expired. Persist its truth,
        // but do not let it authorize a subsequent model request or tool boundary.
        if !state.released {
            self.check(state)?;
        }
        Ok(())
    }

    async fn boundary(&self, checkpoint: Checkpoint, changes: Value) -> Result<()> {
        let mut state = self.state.lock().await;
        self.boundary_locked(&mut state, checkpoint, changes).await
    }

    async fn authorize(&self, checkpoint: &Checkpoint) -> Result<()> {
        let mut state = self.state.lock().await;
        let mut added = Vec::new();
        // Authorize one native call at a time, in the model's original order. A
        // provider can return multiple calls even when parallel_tool_calls=false.
        // Persist/apply its accepted result before dispatching the next side effect.
        for call in checkpoint.pending_calls.iter().take(1) {
            if state.applied.contains(&call.call_id) {
                bail!("applied call is still pending");
            }
            let tool = self
                .configuration
                .tools
                .iter()
                .find(|t| t.name == call.name)
                .ok_or_else(|| anyhow::anyhow!("unknown native tool"))?;
            // Confirmations are handled before this method; never authorize an unanswered request.
            let action = format!(
                "confirm_{}",
                framed_hash(&[&self.claim.turn_id, &call.call_id])
            );
            ensure!(
                tool.permission.as_deref().unwrap_or("always_allow") == "always_allow"
                    || state.interactions.iter().any(|v| v["actionId"] == action
                        && v["phase"] == "resolved"
                        && v["decision"] == "allow"),
                "tool requires policy resolution"
            );
            let arguments = if let Some(envelope) = &tool.argument_envelope {
                let mut value = envelope.clone();
                ensure!(value.is_object(), "invalid tool envelope");
                value["arguments"] = call.arguments.clone();
                value["meta"] = json!({"io.agentscope/toolCallId":call.call_id});
                value
            } else {
                call.arguments.clone()
            };
            let task = task_id(&self.claim.scope, &self.claim.turn_id, &call.call_id);
            let operation = ToolOperation {
                operation_id: call.call_id.clone(),
                task_id: task.clone(),
                execution_id: task,
                native_call_ids: vec![call.call_id.clone()],
                tool_name: tool.operation.clone(),
                arguments: json!({"inline":arguments}),
                configuration_ref: self.configuration.tool_configuration.clone(),
            };
            if let Some(prior) = state.operations.get(&call.call_id) {
                ensure!(
                    canonical(prior)? == canonical(&operation)?,
                    "replayed tool changed identity"
                );
            } else {
                added.push(operation);
            }
        }
        if !added.is_empty() {
            let calls = added
                .iter()
                .map(|op| json!({"nativeCallId":op.operation_id,"position":{}}))
                .collect::<Vec<_>>();
            self.boundary_locked(
                &mut state,
                checkpoint.clone(),
                json!({"toolCalls":calls,"toolOperations":added}),
            )
            .await?;
            for op in added {
                state.operations.insert(op.operation_id.clone(), op);
            }
        }
        Ok(())
    }

    async fn poll_results(&self, calls: &[NativeCall]) -> Result<Vec<(String, ToolResult)>> {
        let mut results = Vec::new();
        for call in calls {
            let (op, known) = {
                let state = self.state.lock().await;
                self.check(&state)?;
                let op = state
                    .operations
                    .get(&call.call_id)
                    .ok_or_else(|| anyhow::anyhow!("tool not authorized"))?
                    .clone();
                let known = state.results.get(&op.task_id).cloned();
                (op, known)
            };
            let result = if let Some(result) = known {
                Some(result)
            } else {
                let response = self.client.post("tools/state", &json!({"protocolVersion":VERSION,"scope":self.claim.scope,"turnId":self.claim.turn_id,"taskId":op.task_id,"executionId":op.execution_id})).await?;
                response
                    .get("result")
                    .filter(|r| !r.is_null())
                    .map(|r| serde_json::from_value::<ToolResult>(r.clone()))
                    .transpose()?
            };
            if let Some(result) = result {
                result.validate(&op, &self.claim.scope)?;
                self.state
                    .lock()
                    .await
                    .results
                    .insert(op.task_id, result.clone());
                results.push((call.call_id.clone(), result));
            }
        }
        Ok(results)
    }

    async fn inputs(&self) -> Result<Vec<Value>> {
        let result = self.client.post("inputs/read", &self.identity()).await?;
        Ok(serde_json::from_value(result["pendingInputs"].clone())?)
    }

    async fn resolve_policies(&self, session: &mut dyn KernelSession) -> Result<bool> {
        for call in session.snapshot().await?.pending_calls {
            let tool = self
                .configuration
                .tools
                .iter()
                .find(|t| t.name == call.name)
                .ok_or_else(|| anyhow::anyhow!("unknown native tool"))?;
            let policy = tool.permission.as_deref().unwrap_or("always_allow");
            if policy == "always_allow" {
                continue;
            }
            let action = format!(
                "confirm_{}",
                framed_hash(&[&self.claim.turn_id, &call.call_id])
            );
            let mut state = self.state.lock().await;
            if state.operations.contains_key(&call.call_id) {
                continue;
            }
            let decision = state
                .interactions
                .iter()
                .find(|v| v["actionId"] == action && v["phase"] == "resolved")
                .map(|v| v["decision"] == "allow");
            drop(state);
            if decision == Some(true) {
                continue;
            }
            if policy != "always_ask" || decision == Some(false) {
                session
                    .inject(vec![NativeOutput {
                        call_id: call.call_id,
                        success: false,
                        content: json!({"error":"Tool permission denied"}),
                    }])
                    .await?;
                self.boundary(session.snapshot().await?, json!({})).await?;
                continue;
            }
            state = self.state.lock().await;
            if !state.interactions.iter().any(|v| v["actionId"] == action) {
                let opened = json!({"actionId":action,"phase":"requested","actionType":"confirmation","request":{"inline":{"toolName":call.name,"arguments":call.arguments,"nativeCallId":call.call_id}},"position":{}});
                self.boundary_locked(
                    &mut state,
                    session.snapshot().await?,
                    json!({"interactionUpdates":[opened]}),
                )
                .await?;
                state.interactions.push(opened);
            }
            drop(state);
            let reply = self
                .inputs()
                .await?
                .into_iter()
                .find(|i| i["type"] == "interaction_response" && i["actionId"] == action);
            if let Some(reply) = reply {
                let answer = &reply["content"]["inline"];
                let allow = answer["approved"] == true || answer["decision"] == "allow";
                if !allow {
                    session
                        .inject(vec![NativeOutput {
                            call_id: call.call_id,
                            success: false,
                            content: json!({"error":"Tool confirmation denied"}),
                        }])
                        .await?;
                }
                let resolved = json!({"actionId":action,"phase":"resolved","inputId":reply["inputId"],"decision":if allow {"allow"} else {"deny"},"position":{}});
                let mut state = self.state.lock().await;
                self.boundary_locked(&mut state,session.snapshot().await?,json!({"interactionUpdates":[resolved],"inputApplications":[{"inputId":reply["inputId"],"status":"applied","position":{}}]})).await?;
                state.interactions.push(resolved);
            } else {
                let captured = session.snapshot().await?;
                session.shutdown().await?;
                self.boundary(captured,json!({"suspension":{"suspensionId":format!("wait_{}",Uuid::new_v4()),"pendingTaskIds":[],"pendingActionIds":[action],"resumePhase":"APPLY_INPUTS","waitPolicy":"all"}})).await?;
                return Ok(false);
            }
        }
        Ok(true)
    }

    async fn finish(&self, checkpoint: Checkpoint, status: &str, output: Value) -> Result<()> {
        self.finish_with_changes(checkpoint, status, output, json!({}))
            .await
    }

    async fn finish_with_changes(
        &self,
        checkpoint: Checkpoint,
        status: &str,
        output: Value,
        mut changes: Value,
    ) -> Result<()> {
        self.client.post("inputs/close", &self.identity()).await?;
        let inputs = self.inputs().await?;
        let cancelled = inputs.iter().any(|v| v["type"] == "cancel");
        let applications = inputs.iter().map(|v| {
            if v["type"] == "cancel" { json!({"inputId":v["inputId"],"status":"applied","position":{}}) }
            else { json!({"inputId":v["inputId"],"status":"rejected","reason":"Turn completed before input application"}) }
        }).collect::<Vec<_>>();
        let bytes = serde_json::to_vec(&output)?;
        let content = if bytes.len() <= 16 * 1024 {
            json!({"inline":output})
        } else {
            json!({"ref":client::put(self.client.as_ref(), &self.claim.scope, &bytes, "public-output-v1").await?})
        };
        let mut terminal =
            json!({"status":if cancelled {"cancelled"} else {status},"output":content});
        if status == "succeeded" && !cancelled {
            if let Some(message) = checkpoint.messages.last() {
                terminal["messageId"] = message.id.clone().into();
            }
        }
        changes["inputApplications"] = json!(applications);
        changes["terminal"] = terminal;
        self.boundary(checkpoint, changes).await
    }

    pub async fn run(self: &Arc<Self>, kernel: &dyn Kernel) -> Result<()> {
        loop {
            let checkpoint = self.state.lock().await.checkpoint.clone();
            let mut session = kernel
                .open(
                    &self.configuration,
                    checkpoint,
                    self.clone(),
                    self.cancel.clone(),
                )
                .await?;
            let work = async {
                let checkpoint = session.snapshot().await?;
                if self.inputs().await?.iter().any(|i| i["type"] == "cancel") {
                    return self.finish(checkpoint, "cancelled", json!([])).await;
                }
                if !checkpoint.pending_calls.is_empty() {
                    if !self.resolve_policies(session.as_mut()).await? { return Ok(()); }
                    let checkpoint = session.snapshot().await?;
                    self.authorize(&checkpoint).await?;
                    let start = Instant::now();
                    let results = loop {
                        let results = self.poll_results(&checkpoint.pending_calls[..checkpoint.pending_calls.len().min(1)]).await?;
                        if checkpoint.pending_calls.is_empty() || !results.is_empty() { break results; }
                        if self.inputs().await?.iter().any(|i| i["type"] == "cancel") {
                            return self.finish(checkpoint, "cancelled", json!([])).await;
                        }
                        if start.elapsed() >= Duration::from_millis(self.configuration.inline_wait_ms.unwrap_or(1000)) {
                            // Preserve partial successes too: apply A before waiting durably for B.
                            break results;
                        }
                        tokio::time::sleep(Duration::from_millis(100)).await;
                    };
                    if !results.is_empty() {
                        let mut outputs = Vec::new();
                        for (call_id, result) in &results {
                            let content = if let Some(value) = result.content.get("inline") { value.clone() }
                                else { let reference = serde_json::from_value(result.content["ref"].clone())?;
                                    serde_json::from_slice(&client::read(self.client.as_ref(), &self.claim.scope, &reference).await?)? };
                            outputs.push(NativeOutput {call_id:call_id.clone(), content, success:result.status == "succeeded"});
                        }
                        session.inject(outputs).await?;
                        let captured = session.snapshot().await?;
                        ensure!(results.iter().all(|(id,_)| captured.applied_calls.contains(id)), "native tool output not recorded");
                        let applications = results.iter().map(|(id,r)| json!({"nativeCallId":id,"resultIds":[r.result_id],"position":{}})).collect::<Vec<_>>();
                        let mut state = self.state.lock().await;
                        self.boundary_locked(&mut state, captured, json!({"resultApplications":applications})).await?;
                        state.applied.extend(results.iter().map(|(id,_)| id.clone()));
                    }
                    let captured = session.snapshot().await?;
                    if !captured.pending_calls.is_empty() {
                        if !results.is_empty() {
                            // The next call has not been authorized yet. Reopen from
                            // this committed checkpoint instead of waiting for a task
                            // that does not exist. No additional model request runs.
                            return Ok(());
                        }
                        session.shutdown().await?;
                        let pending = captured.pending_calls.iter().take(1).map(|c| task_id(&self.claim.scope, &self.claim.turn_id, &c.call_id)).collect::<Vec<_>>();
                        return self.boundary(captured, json!({"suspension":{"suspensionId":format!("wait_{}",Uuid::new_v4()),"pendingTaskIds":pending,"pendingActionIds":[],"resumePhase":"WAIT_TOOLS","waitPolicy":"all"}})).await;
                    }
                }
                if self.state.lock().await.model_calls >= self.configuration.max_model_calls.unwrap_or(100) {
                    return self.finish(session.snapshot().await?,"failed",json!([{"type":"text","text":"Model iteration limit reached."}])).await;
                }
                session.start_or_recover(&self.configuration.prompt).await?;
                let event = loop {
                    tokio::select! {
                        event = session.next() => break event?,
                        _ = tokio::time::sleep(Duration::from_millis(500)) => {
                            if self.inputs().await?.iter().any(|i|i["type"] == "cancel") {
                                let captured = session.suspend().await?;
                                return self.finish(captured,"cancelled",json!([])).await;
                            }
                        }
                    }
                };
                match event {
                    KernelEvent::Tools => {
                        let captured = session.suspend().await?;
                        ensure!(!captured.pending_calls.is_empty(), "tool boundary has no native calls");
                        // Persist before reopening; policy and dispatch happen while the kernel is idle.
                        self.boundary(captured,json!({})).await?;
                        Ok(())
                    }
                    KernelEvent::Complete(output) => {
                        let captured = session.snapshot().await?;
                        session.shutdown().await?;
                        self.finish(captured, "succeeded", output).await
                    }
                }
            }.await;
            let cleanup = session.shutdown().await;
            work?;
            cleanup?;
            if self.state.lock().await.released {
                return Ok(());
            }
        }
    }
}

#[async_trait]
impl ModelGate for Execution {
    fn text_preview(&self, model_call: &str, message_id: &str) -> TextPreview {
        TextPreview::new(
            self.client.clone(),
            self.identity(),
            model_call,
            message_id,
            &self.cancel,
        )
    }
    async fn before_request(
        &self,
        request: Value,
        checkpoint: Checkpoint,
        purpose: &str,
    ) -> Result<String> {
        ensure!(
            matches!(purpose, "reasoning" | "compaction"),
            "invalid model purpose"
        );
        let mut state = self.state.lock().await;
        self.check(&state)?;
        ensure!(
            state.model_calls < self.configuration.max_model_calls.unwrap_or(100),
            "model budget exhausted"
        );
        let id = format!("model_{}", Uuid::new_v4());
        let mut reserve = self.identity();
        reserve["modelCallId"] = id.clone().into();
        reserve["model"] = serde_json::to_value(&self.configuration.model.id)?;
        let reservation = self.client.post("models/reserve", &reserve).await?;
        ensure!(
            reservation["outcome"] == "reserved" && reservation["modelCallId"] == id,
            "model reservation not confirmed"
        );
        let reference = client::put(
            self.client.as_ref(),
            &self.claim.scope,
            &canonical(&request)?,
            "model-request-v1",
        )
        .await?;
        self.boundary_locked(&mut state, checkpoint, json!({"modelUpdates":[{"modelCallId":id,"phase":"dispatch","model":self.configuration.model.id,"purpose":purpose,"requestHash":sha256(canonical(&request)?),"requestRef":reference,"position":{}}]})).await?;
        state.model_calls += 1;
        Ok(id)
    }

    async fn after_response(
        &self,
        id: String,
        response: Value,
        usage: Option<ModelUsage>,
        checkpoint: Checkpoint,
    ) -> Result<()> {
        let reference = client::put(
            self.client.as_ref(),
            &self.claim.scope,
            &serde_json::to_vec(&response)?,
            "model-result-v1",
        )
        .await?;
        let mut update = json!({"modelCallId":id,"phase":"complete","status":"completed","resultRef":reference,"position":{}});
        if let Some(usage) = usage {
            update["usage"] = json!({"inputTokens":usage.input_tokens.to_string(),"outputTokens":usage.output_tokens.to_string(),
                "cacheReadInputTokens":usage.cache_read_input_tokens.to_string(),"cacheWriteInputTokens":usage.cache_write_input_tokens.to_string()});
        } else {
            update["usageUnknown"] = true.into();
        }
        self.boundary(checkpoint, json!({"modelUpdates":[update]}))
            .await
    }

    async fn reject_response(
        &self,
        id: String,
        response: Value,
        usage: Option<ModelUsage>,
        checkpoint: Checkpoint,
    ) -> Result<()> {
        ensure!(
            checkpoint.pending_calls.is_empty(),
            "rejected response has executable calls"
        );
        let reference = client::put(
            self.client.as_ref(),
            &self.claim.scope,
            &serde_json::to_vec(&response)?,
            "model-result-v1",
        )
        .await?;
        let mut update = json!({"modelCallId":id,"phase":"complete","status":"failed","resultRef":reference,"position":{}});
        if let Some(usage) = usage {
            update["usage"] = json!({"inputTokens":usage.input_tokens.to_string(),"outputTokens":usage.output_tokens.to_string(),
                "cacheReadInputTokens":usage.cache_read_input_tokens.to_string(),"cacheWriteInputTokens":usage.cache_write_input_tokens.to_string()});
        } else {
            update["usageUnknown"] = true.into();
        }
        self.finish_with_changes(
            checkpoint,
            "failed",
            json!([{"type":"text","text":"Model response failed managed output validation."}]),
            json!({"modelUpdates":[update]}),
        )
        .await
    }
}

/// Renew outside the native runtime. Any uncertainty revokes the whole local incarnation.
pub async fn run_owned(
    execution: Arc<Execution>,
    kernel: Arc<dyn Kernel>,
    drain: CancellationToken,
) -> Result<()> {
    let renewal = async {
        loop {
            tokio::time::sleep(Duration::from_secs(5)).await;
            execution.renew().await?;
        }
        #[allow(unreachable_code)]
        Ok::<(), anyhow::Error>(())
    };
    tokio::select! {
        result = execution.run(kernel.as_ref()) => { execution.cancel.cancel(); result }
        result = renewal => { execution.cancel.cancel(); result }
        _ = drain.cancelled() => { execution.cancel.cancel(); bail!("worker draining") }
    }
}
