mod support;

use anyhow::Result;
use async_trait::async_trait;
use managed_brain_runtime::client::{ServiceError, Transport, commit};
use managed_brain_runtime::execution::Execution;
use managed_brain_runtime::kernel::{
    Checkpoint, Kernel, KernelEvent, KernelSession, ModelGate, ModelUsage, NativeCall, NativeOutput,
};
use managed_brain_runtime::protocol::{canonical, sha256};
use serde_json::{Value, json};
use std::sync::Arc;
use support::{Service, claim, config};
use tokio::sync::Mutex;
use tokio_util::sync::CancellationToken;

struct TestKernel;

#[tokio::test]
async fn rejected_model_response_atomically_settles_usage_and_fails_without_tools() -> Result<()> {
    for known in [true, false] {
        let service = Arc::new(Service::default());
        let execution = Execution::load(
            service.clone(),
            claim("1"),
            config(),
            CancellationToken::new(),
        )
        .await?;
        let checkpoint = Checkpoint {
            kernel_revision: "test".into(),
            platform_turn_id: String::new(),
            thread_id: "thread".into(),
            turn_id: Some("native-turn".into()),
            history: json!([]),
            pending_calls: Vec::new(),
            applied_calls: Vec::new(),
            messages: Vec::new(),
        };
        let id = execution
            .before_request(json!({"model":"mock"}), checkpoint.clone(), "reasoning")
            .await?;
        execution
            .reject_response(
                id.clone(),
                json!({"managedValidation":"rejected"}),
                known.then_some(ModelUsage {
                    input_tokens: 2237,
                    output_tokens: 166,
                    cache_read_input_tokens: 0,
                    cache_write_input_tokens: 0,
                }),
                checkpoint,
            )
            .await?;
        let state = service.durable.lock().await;
        let changes = &state.commits.last().unwrap()["changes"];
        assert_eq!(changes["terminal"]["status"], "failed");
        let update = &changes["modelUpdates"][0];
        assert_eq!(update["modelCallId"], id);
        assert_eq!(update["status"], "failed");
        if known {
            assert_eq!(update["usage"]["inputTokens"], "2237");
            assert_eq!(update["usage"]["outputTokens"], "166");
            assert!(update.get("usageUnknown").is_none());
        } else {
            assert_eq!(update["usageUnknown"], true);
            assert!(update.get("usage").is_none());
        }
        assert!(
            state
                .commits
                .iter()
                .all(|commit| commit["changes"].get("toolOperations").is_none())
        );
        assert_eq!(
            state
                .transcript
                .iter()
                .filter(|v| v["operation"] == "models/reserve")
                .count(),
            1
        );
    }
    Ok(())
}

struct Session {
    checkpoint: Checkpoint,
    gate: Arc<dyn ModelGate>,
    started: bool,
}

#[async_trait]
impl Kernel for TestKernel {
    async fn open(
        &self,
        _: &managed_brain_runtime::protocol::RunConfiguration,
        checkpoint: Option<Checkpoint>,
        gate: Arc<dyn ModelGate>,
        _: CancellationToken,
    ) -> Result<Box<dyn KernelSession>> {
        Ok(Box::new(Session {
            checkpoint: checkpoint.unwrap_or(Checkpoint {
                kernel_revision: "test".into(),
                platform_turn_id: String::new(),
                thread_id: "thread".into(),
                turn_id: None,
                history: json!([]),
                pending_calls: Vec::new(),
                applied_calls: Vec::new(),
                messages: Vec::new(),
            }),
            gate,
            started: false,
        }))
    }
}

#[async_trait]
impl KernelSession for Session {
    async fn start_or_recover(&mut self, _: &str) -> Result<()> {
        assert!(self.checkpoint.pending_calls.is_empty());
        self.started = true;
        self.checkpoint.turn_id.get_or_insert("native-turn".into());
        let id = self
            .gate
            .before_request(
                json!({"model":"mock","input":self.checkpoint.history}),
                self.checkpoint.clone(),
                "reasoning",
            )
            .await?;
        self.gate
            .after_response(id, json!({"ok":true}), None, self.checkpoint.clone())
            .await
    }
    async fn next(&mut self) -> Result<KernelEvent> {
        if self.checkpoint.applied_calls.is_empty() {
            self.checkpoint.pending_calls = ["call_a", "call_b"]
                .into_iter()
                .map(|id| NativeCall {
                    call_id: id.into(),
                    name: "execute".into(),
                    arguments: json!({"command":id}),
                })
                .collect();
            Ok(KernelEvent::Tools)
        } else {
            Ok(KernelEvent::Complete(
                json!([{ "type":"text","text":"done"}]),
            ))
        }
    }
    async fn snapshot(&self) -> Result<Checkpoint> {
        Ok(self.checkpoint.clone())
    }
    async fn inject(&mut self, outputs: Vec<NativeOutput>) -> Result<()> {
        assert!(!self.started);
        for output in outputs {
            self.checkpoint
                .pending_calls
                .retain(|c| c.call_id != output.call_id);
            assert!(!self.checkpoint.applied_calls.contains(&output.call_id));
            self.checkpoint.applied_calls.push(output.call_id.clone());
            self.checkpoint
                .history
                .as_array_mut()
                .unwrap()
                .push(json!({"call_id":output.call_id,"output":output.content}));
        }
        Ok(())
    }
    async fn suspend(&mut self) -> Result<Checkpoint> {
        self.started = false;
        self.snapshot().await
    }
    async fn shutdown(&mut self) -> Result<()> {
        Ok(())
    }
}

#[tokio::test]
async fn partial_results_survive_owner_change_and_lost_commit_receipt() -> Result<()> {
    let service = Arc::new(Service::default());
    service.durable.lock().await.lose_commit = true;
    let first = Execution::load(
        service.clone(),
        claim("1"),
        config(),
        CancellationToken::new(),
    )
    .await?;
    first.run(&TestKernel).await?;
    drop(first);
    {
        let mut state = service.durable.lock().await;
        let applied_a = state
            .commits
            .iter()
            .position(|c| {
                c["changes"]["resultApplications"]
                    .as_array()
                    .is_some_and(|items| items.iter().any(|v| v["nativeCallId"] == "call_a"))
            })
            .unwrap();
        let dispatched_b = state
            .commits
            .iter()
            .position(|c| {
                c["changes"]["toolOperations"]
                    .as_array()
                    .is_some_and(|items| items.iter().any(|v| v["operationId"] == "call_b"))
            })
            .unwrap();
        assert!(
            applied_a < dispatched_b,
            "A must be durably applied before B can execute"
        );
        assert_eq!(
            state.bundle["resultApplications"].as_array().unwrap().len(),
            1
        );
        assert!(
            state
                .transcript
                .iter()
                .any(|v| v["operation"] == "commits/receipt")
        );
        state.finish_b = true;
        state.fence = serde_json::to_value(claim("2").fence)?;
    }
    let second = Execution::load(
        service.clone(),
        claim("2"),
        config(),
        CancellationToken::new(),
    )
    .await?;
    second.run(&TestKernel).await?;
    let state = service.durable.lock().await;
    assert_eq!(state.bundle["toolOperations"].as_array().unwrap().len(), 2);
    assert_eq!(
        state.bundle["resultApplications"].as_array().unwrap().len(),
        2
    );
    assert_eq!(state.bundle["terminal"]["status"], "succeeded");
    Ok(())
}

#[tokio::test]
async fn failed_authorization_does_not_poll_or_execute_hands() -> Result<()> {
    let service = Arc::new(Service::default());
    let execution = Execution::load(
        service.clone(),
        claim("1"),
        config(),
        CancellationToken::new(),
    )
    .await?;
    service.durable.lock().await.deny_commits = true;
    assert!(execution.run(&TestKernel).await.is_err());
    let state = service.durable.lock().await;
    assert!(
        !state
            .transcript
            .iter()
            .any(|v| v["operation"] == "tools/state")
    );
    assert_eq!(state.bundle["toolOperations"], json!([]));
    Ok(())
}

#[tokio::test]
async fn always_ask_suspends_without_dispatch_until_original_reply() -> Result<()> {
    let service = Arc::new(Service::default());
    let mut configuration = config();
    configuration.tools[0].permission = Some("always_ask".into());
    let execution = Execution::load(
        service.clone(),
        claim("1"),
        configuration,
        CancellationToken::new(),
    )
    .await?;
    execution.run(&TestKernel).await?;
    let state = service.durable.lock().await;
    assert_eq!(state.bundle["toolOperations"], json!([]));
    let suspension = &state.commits.last().unwrap()["changes"]["suspension"];
    assert_eq!(suspension["pendingActionIds"].as_array().unwrap().len(), 1);
    assert_eq!(suspension["resumePhase"], "APPLY_INPUTS");
    Ok(())
}

#[tokio::test]
async fn confirmation_replies_survive_takeover_and_only_allow_the_original_call() -> Result<()> {
    let service = Arc::new(Service::default());
    let mut configuration = config();
    configuration.tools[0].permission = Some("always_ask".into());
    for epoch in ["1", "2", "3"] {
        service.durable.lock().await.fence = serde_json::to_value(claim(epoch).fence)?;
        let execution = Execution::load(
            service.clone(),
            claim(epoch),
            configuration.clone(),
            CancellationToken::new(),
        )
        .await?;
        execution.run(&TestKernel).await?;
        let mut state = service.durable.lock().await;
        if epoch != "3" {
            assert_eq!(state.bundle["toolOperations"], json!([]));
            let action = state.commits.last().unwrap()["changes"]["suspension"]["pendingActionIds"]
                [0]
            .clone();
            state.bundle["pendingInputs"] = json!([{
                "inputId":format!("reply-{epoch}"), "type":"interaction_response", "actionId":action,
                "content":{"inline":{"approved":epoch == "1"}}
            }]);
        }
    }
    let state = service.durable.lock().await;
    assert_eq!(state.bundle["terminal"]["status"], "succeeded");
    let operations = state.bundle["toolOperations"].as_array().unwrap();
    assert_eq!(operations.len(), 1);
    assert_eq!(operations[0]["operationId"], "call_a");
    assert_eq!(
        state.bundle["inputApplications"].as_array().unwrap().len(),
        2
    );
    assert_eq!(
        state.bundle["resultApplications"].as_array().unwrap().len(),
        1
    );
    assert_eq!(state.bundle["pendingInputs"], json!([]));
    Ok(())
}

struct GateFaultTransport {
    service: Arc<Service>,
    late_receipt: bool,
}

#[async_trait]
impl Transport for GateFaultTransport {
    async fn post(&self, op: &str, request: &Value) -> Result<Value> {
        if self.late_receipt && op == "executions/renew" {
            return Ok(
                json!({"fence":request["fence"], "serverTime":"2026-10-28T00:00:00Z", "expiresAt":"2026-10-28T00:00:02.100Z"}),
            );
        }
        if !self.late_receipt && op == "models/reserve" {
            return Ok(json!({"outcome":"reserved","modelCallId":"another-request"}));
        }
        let result = self.service.post(op, request).await;
        if self.late_receipt && op == "commits" {
            tokio::time::sleep(std::time::Duration::from_millis(1200)).await;
        }
        result
    }
}

#[tokio::test]
async fn late_dispatch_receipt_cannot_authorize_provider_after_lease_expiry() -> Result<()> {
    let service = Arc::new(Service::default());
    let transport = Arc::new(GateFaultTransport {
        service: service.clone(),
        late_receipt: true,
    });
    let execution =
        Execution::load(transport, claim("1"), config(), CancellationToken::new()).await?;
    assert!(execution.run(&TestKernel).await.is_err());
    let state = service.durable.lock().await;
    let models = state.bundle["modelUpdates"].as_array().unwrap();
    assert_eq!(models.len(), 1);
    assert_eq!(models[0]["phase"], "dispatch");
    assert_eq!(state.bundle["toolOperations"], json!([]));
    Ok(())
}

#[tokio::test]
async fn mismatched_reservation_cannot_dispatch_a_model() -> Result<()> {
    let service = Arc::new(Service::default());
    let transport = Arc::new(GateFaultTransport {
        service: service.clone(),
        late_receipt: false,
    });
    let execution =
        Execution::load(transport, claim("1"), config(), CancellationToken::new()).await?;
    assert!(execution.run(&TestKernel).await.is_err());
    let state = service.durable.lock().await;
    assert_eq!(state.bundle["modelUpdates"], json!([]));
    assert_eq!(state.bundle["toolOperations"], json!([]));
    Ok(())
}

#[tokio::test]
async fn denied_tools_never_reach_hands() -> Result<()> {
    let service = Arc::new(Service::default());
    let mut configuration = config();
    configuration.tools[0].permission = Some("always_deny".into());
    let execution = Execution::load(
        service.clone(),
        claim("1"),
        configuration,
        CancellationToken::new(),
    )
    .await?;
    execution.run(&TestKernel).await?;
    let state = service.durable.lock().await;
    assert_eq!(state.bundle["toolOperations"], json!([]));
    assert_eq!(state.bundle["terminal"]["status"], "succeeded");
    Ok(())
}

#[tokio::test]
async fn budget_exhaustion_settles_without_another_provider_request() -> Result<()> {
    let service = Arc::new(Service::default());
    let mut configuration = config();
    configuration.max_model_calls = Some(0);
    let execution = Execution::load(
        service.clone(),
        claim("1"),
        configuration,
        CancellationToken::new(),
    )
    .await?;
    execution.run(&TestKernel).await?;
    let state = service.durable.lock().await;
    assert_eq!(state.bundle["terminal"]["status"], "failed");
    assert!(
        !state
            .transcript
            .iter()
            .any(|v| v["operation"] == "models/reserve")
    );
    Ok(())
}

struct ReceiptTransport {
    requests: Mutex<Vec<(String, Value)>>,
    missing: bool,
}
#[async_trait]
impl Transport for ReceiptTransport {
    async fn post(&self, op: &str, request: &Value) -> Result<Value> {
        let mut requests = self.requests.lock().await;
        requests.push((op.into(), request.clone()));
        if requests.len() == 1 {
            return Err(ServiceError::Unconfirmed.into());
        }
        if op == "commits/receipt" {
            if self.missing {
                return Err(ServiceError::Rejected {
                    status: 404,
                    code: "receipt_not_found".into(),
                }
                .into());
            }
            return Err(ServiceError::Unconfirmed.into());
        }
        Ok(
            json!({"commitId":request["commitId"],"contentHash":sha256(canonical(request)?),"brainRevision":"1","nativeCursor":"1","outcome":"committed"}),
        )
    }
}

#[tokio::test]
async fn uncertain_receipt_never_authorizes_replacement_and_explicit_missing_retries_identically()
-> Result<()> {
    let request = json!({"commitId":"same","expectedBrainRevision":"0","scope":claim("1").scope,"turnId":"t","nativeState":{"nativeCursor":"1"}});
    for missing in [false, true] {
        let transport = ReceiptTransport {
            requests: Mutex::new(Vec::new()),
            missing,
        };
        assert_eq!(commit(&transport, &request).await.is_ok(), missing);
        let requests = transport.requests.lock().await;
        assert_eq!(requests.len(), if missing { 3 } else { 2 });
        if missing {
            assert_eq!(requests[0].1, requests[2].1);
        }
    }
    Ok(())
}
