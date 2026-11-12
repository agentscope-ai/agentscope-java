#![allow(dead_code)]
use anyhow::{Result, ensure};
use async_trait::async_trait;
use base64::{Engine, engine::general_purpose::STANDARD};
use managed_brain_runtime::client::{ServiceError, Transport};
use managed_brain_runtime::protocol::{Claim, RunConfiguration, canonical, framed_hash, sha256};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::HashMap;
use tokio::sync::Mutex;

#[derive(Serialize, Deserialize)]
pub struct Durable {
    pub bundle: Value,
    pub objects: HashMap<String, Value>,
    pub receipts: HashMap<String, Value>,
    pub commits: Vec<Value>,
    pub transcript: Vec<Value>,
    pub finish_b: bool,
    pub lose_commit: bool,
    pub deny_commits: bool,
    pub fence: Value,
}

impl Default for Durable {
    fn default() -> Self {
        Self {
            bundle: json!({"brainRevision":"0","acceptedResults":[],"toolOperations":[],"resultApplications":[],"inputApplications":[],"pendingInputs":[],"interactionUpdates":[],"modelUpdates":[]}),
            objects: HashMap::new(),
            receipts: HashMap::new(),
            commits: Vec::new(),
            transcript: Vec::new(),
            finish_b: false,
            lose_commit: false,
            deny_commits: false,
            fence: serde_json::to_value(claim("1").fence).unwrap(),
        }
    }
}

#[derive(Default)]
pub struct Service {
    pub durable: Mutex<Durable>,
    pub disk: Option<std::path::PathBuf>,
    pub stop_after_application: bool,
    pub tool_content: Option<Value>,
}

impl Service {
    pub async fn result(&self, call: &str) -> Value {
        let state = self.durable.lock().await;
        let op = state.bundle["toolOperations"]
            .as_array()
            .unwrap()
            .iter()
            .find(|v| v["operationId"] == call)
            .unwrap();
        result_for(op)
    }
}

fn result_for(operation: &Value) -> Value {
    let id = operation["taskId"].as_str().unwrap();
    json!({"resultId":format!("result_{}",framed_hash(&[id,id])),"taskId":id,"executionId":id,"status":"succeeded",
        "content":{"inline":{"text":format!("original Hands result {}",operation["operationId"].as_str().unwrap())}},"artifacts":[],
        "output":{"mode":"none","throughSourceSeq":"0","byteLength":"0"}})
}

#[async_trait]
impl Transport for Service {
    async fn post(&self, operation: &str, request: &Value) -> Result<Value> {
        let mut state = self.durable.lock().await;
        state
            .transcript
            .push(json!({"operation":operation,"request":request}));
        if request.get("fence").is_some() {
            ensure!(request["fence"] == state.fence, "stale fence");
        }
        match operation {
            "previews/interest" => Ok(json!({"interested":true})),
            "previews/publish" => {
                let updates = state.bundle["modelUpdates"].as_array().unwrap();
                ensure!(
                    updates
                        .iter()
                        .any(|v| v["modelCallId"] == request["modelCallId"]
                            && v["phase"] == "dispatch")
                        && !updates
                            .iter()
                            .any(|v| v["modelCallId"] == request["modelCallId"]
                                && v["phase"] == "complete"),
                    "preview outside active model call"
                );
                Ok(json!({"accepted":true}))
            }
            "executions/renew" => Ok(
                json!({"fence":state.fence,"serverTime":"2026-10-28T00:00:00Z","expiresAt":"2026-10-28T00:01:00Z"}),
            ),
            "recovery/read" => Ok(json!({"bundle":state.bundle})),
            "objects/write" => {
                let bytes = STANDARD.decode(request["dataBase64"].as_str().unwrap())?;
                ensure!(sha256(&bytes) == request["sha256"], "bad object hash");
                let reference = json!({"scope":request["scope"],"backendId":"objects","key":request["sha256"],"sha256":request["sha256"],"size":request["size"],"mediaType":request["mediaType"],"formatVersion":request["formatVersion"]});
                state.objects.insert(
                    request["sha256"].as_str().unwrap().into(),
                    json!({"ref":reference,"dataBase64":request["dataBase64"]}),
                );
                Ok(json!({"ref":reference}))
            }
            "objects/read" => Ok(state.objects[request["ref"]["sha256"].as_str().unwrap()].clone()),
            "commits" => {
                if state.deny_commits {
                    return Err(ServiceError::Rejected {
                        status: 409,
                        code: "stale_fence".into(),
                    }
                    .into());
                }
                let id = request["commitId"].as_str().unwrap().to_owned();
                if let Some(receipt) = state.receipts.get(&id) {
                    ensure!(
                        receipt["contentHash"] == sha256(canonical(request)?),
                        "changed commit retry"
                    );
                    return Ok(receipt.clone());
                }
                ensure!(
                    request["expectedBrainRevision"] == state.bundle["brainRevision"],
                    "stale revision"
                );
                let revision = state.bundle["brainRevision"]
                    .as_str()
                    .unwrap()
                    .parse::<u64>()?
                    + 1;
                state.bundle["brainRevision"] = revision.to_string().into();
                state.bundle["nativeState"] = request["nativeState"].clone();
                for key in [
                    "toolOperations",
                    "resultApplications",
                    "inputApplications",
                    "interactionUpdates",
                    "modelUpdates",
                ] {
                    if let Some(items) = request["changes"][key].as_array() {
                        state.bundle[key]
                            .as_array_mut()
                            .unwrap()
                            .extend(items.clone());
                    }
                }
                if let Some(applications) = request["changes"]["inputApplications"].as_array() {
                    state.bundle["pendingInputs"]
                        .as_array_mut()
                        .unwrap()
                        .retain(|input| {
                            !applications
                                .iter()
                                .any(|application| application["inputId"] == input["inputId"])
                        });
                }
                if request["changes"].get("terminal").is_some() {
                    state.bundle["terminal"] = request["changes"]["terminal"].clone();
                }
                let receipt = json!({"commitId":id,"contentHash":sha256(canonical(request)?),"brainRevision":revision.to_string(),"nativeCursor":request["nativeState"]["nativeCursor"],"outcome":"committed"});
                state.receipts.insert(id, receipt.clone());
                state.commits.push(request.clone());
                if let Some(path) = &self.disk {
                    let temporary = path.with_extension("tmp");
                    std::fs::write(&temporary, serde_json::to_vec(&*state)?)?;
                    std::fs::rename(temporary, path)?;
                }
                // Simulate a committed result application whose HTTP receipt never arrives.
                // The parent test kills this process while it is waiting here.
                if self.stop_after_application
                    && request["changes"]["resultApplications"]
                        .as_array()
                        .is_some_and(|items| !items.is_empty())
                {
                    std::future::pending::<()>().await;
                }
                if state.lose_commit {
                    state.lose_commit = false;
                    return Err(ServiceError::Unconfirmed.into());
                }
                Ok(receipt)
            }
            "commits/receipt" => state
                .receipts
                .get(request["commitId"].as_str().unwrap())
                .cloned()
                .ok_or_else(|| {
                    ServiceError::Rejected {
                        status: 404,
                        code: "receipt_not_found".into(),
                    }
                    .into()
                }),
            "tools/state" => {
                let op = state.bundle["toolOperations"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .find(|v| v["taskId"] == request["taskId"])
                    .cloned()
                    .unwrap();
                if op["operationId"] == "call_a" || state.finish_b {
                    let mut result = result_for(&op);
                    if let Some(content) = &self.tool_content {
                        result["content"]["inline"] = content.clone();
                    }
                    if !state.bundle["acceptedResults"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .any(|v| v["resultId"] == result["resultId"])
                    {
                        state.bundle["acceptedResults"]
                            .as_array_mut()
                            .unwrap()
                            .push(result.clone());
                    }
                    Ok(json!({"state":"completed","result":result}))
                } else {
                    Ok(json!({"state":"running"}))
                }
            }
            "inputs/read" => Ok(json!({"pendingInputs":state.bundle["pendingInputs"]})),
            "inputs/close" => Ok(json!({"throughSequence":"0"})),
            "models/reserve" => {
                Ok(json!({"outcome":"reserved","modelCallId":request["modelCallId"]}))
            }
            _ => anyhow::bail!("unexpected operation {operation}"),
        }
    }
}

pub fn claim(epoch: &str) -> Claim {
    serde_json::from_value(json!({"scope":{"tenant":"tenant","namespace":"test","sessionId":"s1"},"turnId":"platform-turn",
        "fence":{"owner":{"instanceId":"brain","incarnationId":epoch},"epoch":epoch,"executionToken":format!("token-{epoch}")},"expiresAt":"2026-10-28T00:01:00Z"})).unwrap()
}

pub fn config() -> RunConfiguration {
    serde_json::from_value(json!({"prompt":"Run both tools then summarize their original results.","system":"Use the provided tools.","cwd":"/workspace","maxModelCalls":10,"inlineWaitMs":0,"model":{"provider":"openai","id":"gpt-5.1-codex"},
        "toolConfiguration":{"scope":claim("1").scope,"backendId":"objects","key":"config","sha256":"0".repeat(64),"size":"2","mediaType":"application/json","formatVersion":"tool-configuration-v1"},
        "tools":[{"name":"execute","operation":"sandbox.exec","description":"Execute command on Hands","parameters":{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]},"permission":"always_allow"}]})).unwrap()
}
