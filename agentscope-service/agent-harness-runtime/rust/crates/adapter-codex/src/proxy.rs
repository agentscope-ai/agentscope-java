use crate::{capture, model::ModelBinding, preview::StreamPreviews};
use anyhow::{Result, ensure};
use axum::Router;
use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::post;
use codex_core::CodexThread;
use managed_brain_runtime::kernel::{ModelGate, ModelUsage};
use managed_brain_runtime::protocol::MAX_OBJECT;
use reqwest::{Client, redirect::Policy};
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use tokio::net::TcpListener;
use tokio::sync::{Mutex, OnceCell};
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

pub(crate) struct ModelProxy {
    pub base_url: String,
    pub calls: Arc<Mutex<HashSet<String>>>,
    pub stop: CancellationToken,
}

struct ProxyState {
    binding: Arc<ModelBinding>,
    client: Client,
    allowed: HashSet<String>,
    tool_schemas: HashMap<String, Value>,
    calls: Arc<Mutex<HashSet<String>>>,
    thread: Arc<OnceCell<Arc<CodexThread>>>,
    gate: Arc<dyn ModelGate>,
    used: AtomicBool,
    cancel: CancellationToken,
}

impl ModelProxy {
    pub async fn start(
        binding: Arc<ModelBinding>,
        tool_schemas: HashMap<String, Value>,
        thread: Arc<OnceCell<Arc<CodexThread>>>,
        gate: Arc<dyn ModelGate>,
        cancel: CancellationToken,
    ) -> Result<Self> {
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let prefix = format!("/{}/v1", Uuid::new_v4());
        let base_url = format!("http://{}{}", listener.local_addr()?, prefix);
        let calls = Arc::new(Mutex::new(HashSet::new()));
        let stop = cancel.child_token();
        let timeout = Duration::from_secs(binding.request_timeout_seconds());
        let state = Arc::new(ProxyState {
            binding,
            client: Client::builder()
                .redirect(Policy::none())
                .timeout(timeout)
                .build()?,
            allowed: tool_schemas.keys().cloned().collect(),
            tool_schemas,
            calls: calls.clone(),
            thread,
            gate,
            used: AtomicBool::new(false),
            cancel: stop.clone(),
        });
        let app = Router::new()
            .route(&format!("{prefix}/responses"), post(forward))
            .layer(DefaultBodyLimit::max(MAX_OBJECT))
            .with_state(state);
        let stopped = stop.clone();
        tokio::spawn(async move {
            let _ = axum::serve(listener, app)
                .with_graceful_shutdown(stopped.cancelled_owned())
                .await;
        });
        Ok(Self {
            base_url,
            calls,
            stop,
        })
    }
}

impl Drop for ModelProxy {
    fn drop(&mut self) {
        self.stop.cancel();
    }
}

async fn forward(State(state): State<Arc<ProxyState>>, body: Bytes) -> Response {
    let result = tokio::select! {
        result = forward_once(&state,body) => result,
        _ = state.cancel.cancelled() => return StatusCode::CONFLICT.into_response(),
    };
    match result {
        Ok(bytes) => ([("content-type", "text/event-stream")], bytes).into_response(),
        // No upstream body, prompts, arguments or credentials in error logs/responses.
        Err(_) => (StatusCode::BAD_GATEWAY, "Managed model request failed").into_response(),
    }
}

async fn forward_once(state: &ProxyState, bytes: Bytes) -> Result<Vec<u8>> {
    let mut request: Value = serde_json::from_slice(&bytes)?;
    let compaction = is_compaction(&request);
    // A native summary may precede the reasoning response. Both cross the same
    // persistent reservation/usage gate. Additional reasoning still requires a
    // fresh committed native session; implicit continuations cannot bypass it.
    if !compaction {
        ensure!(
            !state.used.swap(true, Ordering::SeqCst),
            "native session already used its model request"
        );
    }
    state.binding.apply_request(&mut request)?;
    if let Some(tools) = request["tools"].as_array_mut() {
        tools.retain(|tool| {
            tool["type"] == "function"
                && tool["name"]
                    .as_str()
                    .is_some_and(|name| state.allowed.contains(name))
        });
        // Core's typed schema representation drops JSON Schema constraints such
        // as minimum/maximum. Preserve the declared MCP schema at the model
        // boundary, before both durable budgeting and provider dispatch.
        for tool in tools {
            if let Some(schema) = tool["name"]
                .as_str()
                .and_then(|name| state.tool_schemas.get(name))
            {
                tool["parameters"] = schema.clone();
            }
        }
    }
    request["store"] = false.into();
    request["stream"] = true.into();
    let thread = state
        .thread
        .get()
        .ok_or_else(|| anyhow::anyhow!("native thread not installed"))?;
    let checkpoint = capture(thread).await?;
    let thread_id = checkpoint.thread_id.clone();
    let id = state
        .gate
        .before_request(
            request.clone(),
            checkpoint,
            if compaction {
                "compaction"
            } else {
                "reasoning"
            },
        )
        .await?;
    let mut response = state
        .client
        .post(state.binding.url.clone())
        .bearer_auth(&state.binding.key)
        .json(&request)
        .send()
        .await?;
    ensure!(
        response.status().is_success(),
        "model endpoint rejected request"
    );
    let mut body = Vec::new();
    let mut previews = StreamPreviews::new(state.gate.clone(), id.clone(), thread_id);
    while let Some(chunk) = response.chunk().await? {
        ensure!(
            body.len() + chunk.len() <= MAX_OBJECT,
            "model response exceeds limit"
        );
        body.extend_from_slice(&chunk);
        if !compaction {
            previews.accept(&chunk);
        }
    }
    // Buffer and validate the whole response before exposing any executable item to Core.
    // This makes the tool allowlist an enforcement point, including provider misbehaviour.
    let events = parse_sse(&body)?;
    previews.finish().await;
    let usage = response_usage(&events);
    let calls = match validate_events(&events, &state.allowed) {
        Ok(calls) => calls,
        Err(_) => {
            state
                .gate
                .reject_response(
                    id,
                    json!({"events":events,"managedValidation":"rejected"}),
                    usage,
                    capture(thread).await?,
                )
                .await?;
            anyhow::bail!("model response failed managed output validation");
        }
    };
    *state.calls.lock().await = calls;
    state
        .gate
        .after_response(id, json!({"events":events}), usage, capture(thread).await?)
        .await?;
    Ok(body)
}

fn is_compaction(request: &Value) -> bool {
    request["tools"].as_array().is_none_or(Vec::is_empty)
        && request["input"]
            .as_array()
            .and_then(|items| items.last())
            .and_then(|item| item["content"].as_array())
            .is_some_and(|content| {
                content.iter().any(|part| {
                    part["text"].as_str() == Some(codex_core::compact::SUMMARIZATION_PROMPT)
                })
            })
}

fn response_usage(events: &[Value]) -> Option<ModelUsage> {
    let usage = &events.iter().rev().find(|event| {
        matches!(
            event["type"].as_str(),
            Some("response.completed" | "response.incomplete" | "response.failed")
        )
    })?["response"]["usage"];
    let input_tokens = usage["input_tokens"].as_u64()?;
    let output_tokens = usage["output_tokens"].as_u64()?;
    let cached = usage.pointer("/input_tokens_details/cached_tokens");
    let cache_read_input_tokens = match cached {
        Some(value) => value.as_u64()?,
        None => 0,
    };
    let cache_write_input_tokens = match usage.pointer("/input_tokens_details/cache_write_tokens") {
        Some(value) => value.as_u64()?,
        None => 0,
    };
    // Missing or inconsistent accounting stays unknown; never invent zero usage.
    if cache_read_input_tokens.checked_add(cache_write_input_tokens)? > input_tokens
        || input_tokens > i64::MAX as u64
        || output_tokens > i64::MAX as u64
    {
        return None;
    }
    Some(ModelUsage {
        input_tokens,
        output_tokens,
        cache_read_input_tokens,
        cache_write_input_tokens,
    })
}

fn parse_sse(body: &[u8]) -> Result<Vec<Value>> {
    let text = std::str::from_utf8(body)?.replace("\r\n", "\n");
    let mut events = Vec::new();
    for frame in text.split("\n\n") {
        let data = frame
            .lines()
            .filter_map(|line| line.strip_prefix("data:").map(str::trim_start))
            .collect::<Vec<_>>()
            .join("\n");
        if data.is_empty() || data == "[DONE]" {
            continue;
        }
        let value: Value = serde_json::from_str(&data)?;
        events.push(value);
    }
    Ok(events)
}

fn validate_events(events: &[Value], allowed: &HashSet<String>) -> Result<HashSet<String>> {
    let mut calls = HashSet::new();
    let mut completed = false;
    for value in events {
        if let Some(item) = value.get("item") {
            validate_item(item, allowed, &mut calls)?;
        }
        if let Some(items) = value["response"]["output"].as_array() {
            for item in items {
                validate_item(item, allowed, &mut calls)?;
            }
        }
        completed |= value["type"] == "response.completed";
        ensure!(
            value["type"] != "response.failed" && value["type"] != "error",
            "model generation failed"
        );
    }
    ensure!(completed, "incomplete model stream");
    Ok(calls)
}

#[cfg(test)]
fn validate_sse(body: &[u8], allowed: &HashSet<String>) -> Result<(Vec<Value>, HashSet<String>)> {
    let events = parse_sse(body)?;
    let calls = validate_events(&events, allowed)?;
    Ok((events, calls))
}

fn validate_item(
    item: &Value,
    allowed: &HashSet<String>,
    calls: &mut HashSet<String>,
) -> Result<()> {
    match item["type"].as_str() {
        Some("function_call") => {
            ensure!(
                item["namespace"].is_null()
                    && item["name"]
                        .as_str()
                        .is_some_and(|name| allowed.contains(name)),
                "unmanaged tool response"
            );
            let id = item["call_id"]
                .as_str()
                .ok_or_else(|| anyhow::anyhow!("missing call ID"))?;
            ensure!(!id.is_empty(), "empty call ID");
            calls.insert(id.to_owned());
        }
        Some("message" | "reasoning" | "compaction") => {}
        _ => anyhow::bail!("unsupported model output item"),
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejected_tool_names_keep_known_usage_without_becoming_executable() {
        let allowed = HashSet::from(["hands_terminal__terminal_start".to_owned()]);
        for name in [
            " hands_terminal__terminal_start",
            "handson_terminal__terminal_start_2",
        ] {
            let stream = format!(
                "data: {}\n\n",
                json!({"type":"response.completed","response":{
                "output":[{"type":"function_call","name":name,"call_id":"bad","arguments":"{}"}],
                "usage":{"input_tokens":2237,"output_tokens":166}}})
            );
            let events = parse_sse(stream.as_bytes()).unwrap();
            assert!(validate_events(&events, &allowed).is_err());
            let usage = response_usage(&events).unwrap();
            assert_eq!(usage.input_tokens + usage.output_tokens, 2403);
        }
    }

    #[test]
    fn incomplete_response_keeps_usage_but_cannot_dispatch_tools() {
        let events = [json!({"type":"response.incomplete","response":{
            "usage":{"input_tokens":200,"output_tokens":8192},
            "incomplete_details":{"reason":"max_output_tokens"}}})];
        assert!(validate_events(&events, &HashSet::new()).is_err());
        assert_eq!(response_usage(&events).unwrap().output_tokens, 8192);
    }

    #[test]
    fn compaction_requires_the_native_prompt_and_no_tools() {
        let mut request = json!({"tools":[], "input":[{"role":"user", "content":[{
            "type":"input_text", "text":codex_core::compact::SUMMARIZATION_PROMPT}]}]});
        assert!(is_compaction(&request));
        request["tools"] = json!([{"type":"function","name":"hands_write"}]);
        assert!(!is_compaction(&request));
        request["tools"] = json!([]);
        request["input"][0]["content"][0]["text"] = "ordinary user request".into();
        assert!(!is_compaction(&request));
    }

    #[test]
    fn usage_reports_totals_and_cache_subsets_without_inventing_missing_counts() {
        let events = [
            json!({"type":"response.completed","response":{"usage":{"input_tokens":50,"output_tokens":8,
            "input_tokens_details":{"cached_tokens":20,"cache_write_tokens":10}}}}),
        ];
        let usage = response_usage(&events).unwrap();
        assert_eq!(
            (
                usage.input_tokens,
                usage.output_tokens,
                usage.cache_read_input_tokens,
                usage.cache_write_input_tokens
            ),
            (50, 8, 20, 10)
        );
        for usage in [
            json!(null),
            json!({"input_tokens":10}),
            json!({"input_tokens":10,"output_tokens":2,"input_tokens_details":{"cached_tokens":11}}),
            json!({"input_tokens":10,"output_tokens":-1}),
            json!({"input_tokens":18446744073709551615u64,"output_tokens":1}),
        ] {
            assert!(
                response_usage(&[json!({"type":"response.completed","response":{"usage":usage}})])
                    .is_none()
            );
        }
    }
    #[test]
    fn rejects_builtin_execution_before_core_can_consume_it() {
        let allowed = HashSet::from(["hands_execute".to_owned()]);
        for kind in [
            json!({"type":"function_call","name":"exec_command","call_id":"a"}),
            json!({"type":"custom_tool_call","name":"apply_patch","call_id":"a"}),
        ] {
            let stream = format!(
                "data: {}\n\ndata: {{\"type\":\"response.completed\"}}\n\n",
                json!({"type":"response.output_item.done","item":kind})
            );
            assert!(validate_sse(stream.as_bytes(), &allowed).is_err());
        }
    }
    #[test]
    fn incomplete_stream_cannot_dispatch_tools() {
        assert!(
            validate_sse(
                b"data: {\"type\":\"response.created\"}\n\n",
                &HashSet::new()
            )
            .is_err()
        );
    }
}
