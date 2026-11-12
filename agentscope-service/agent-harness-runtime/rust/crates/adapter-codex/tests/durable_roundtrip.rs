#[path = "../../runtime/tests/support/mod.rs"]
mod support;

use anyhow::{Result, ensure};
use axum::{Json, Router, extract::State, routing::post};
use managed_adapter_codex::CodexKernel;
use managed_brain_runtime::client;
use managed_brain_runtime::execution::Execution;
use serde_json::{Value, json};
use std::sync::Arc;
use std::time::Duration;
use support::{Durable, Service, claim, config};
use tokio::net::TcpListener;
use tokio::sync::Mutex;
use tokio_util::sync::CancellationToken;

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn per_agent_model_bindings_reach_native_http_without_sharing_credentials() -> Result<()> {
    use axum::http::HeaderMap;
    let requests = Arc::new(Mutex::new(Vec::new()));
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let base = format!("http://{}", listener.local_addr()?);
    let mut app = Router::new();
    for route in ["alpha", "beta"] {
        let requests = requests.clone();
        app = app.route(&format!("/{route}/responses"), post(move |headers: HeaderMap, Json(body): Json<Value>| {
            let requests = requests.clone();
            async move {
                requests.lock().await.push(json!({"route":route,"auth":headers["authorization"].to_str().unwrap(),"body":body}));
                let item = json!({"type":"message","id":format!("{route}-message"),"role":"assistant","content":[{"type":"output_text","text":"configured"}]});
                let events = [json!({"type":"response.created","response":{"id":route}}),
                    json!({"type":"response.output_item.done","item":item}),
                    json!({"type":"response.completed","response":{"id":route,"output":[item]}})];
                ([("content-type","text/event-stream")], events.iter().map(|e|format!("data: {e}\n\n")).collect::<String>())
            }
        }));
    }
    let server = tokio::spawn(async move { axum::serve(listener, app).await });
    let kernel = CodexKernel::new(
        "http://127.0.0.1:1/unreachable".into(),
        "wrong-default".into(),
        "openai".into(),
    )
    .with_credential_resolver(|name| match name {
        "MANAGED_MODEL_ALPHA" => Some("alpha-only-secret".into()),
        "PI_MODEL_BETA" => Some("beta-only-secret".into()),
        _ => None,
    });
    let alpha = Arc::new(Service::default());
    let beta = Arc::new(Service::default());
    let run = |route: &str, credential: &str, reasoning: bool, service: Arc<Service>| {
        let mut configuration = config();
        configuration.tools[0].parameters = json!({
            "type":"object", "properties":{
                "command":{"type":"string","minLength":1,"pattern":"^[a-z]"},
                "seconds":{"type":"integer","minimum":0,"maximum":40,"default":0}
            }, "required":["command"],"additionalProperties":false
        });
        configuration.system = Some(format!("Managed {route} agent instructions."));
        configuration.model = serde_json::from_value(json!({
            "provider":format!("provider-{route}"),"id":format!("model-{route}"),
            "api":"openai-responses","baseUrl":format!("{base}/{route}"),
            "credentialEnv":credential,"contextWindow":32000,"outputLimit":2048,
            "reasoning":reasoning,"cost":{"input":1,"output":2,"cacheRead":0.1,"cacheWrite":1},
            "parameters":if reasoning {json!({"temperature":0.2,"maxTokens":512,"reasoning":"low"})}
                else {json!({"temperature":0.8})}
        }))
        .unwrap();
        let kernel = &kernel;
        async move {
            let execution =
                Execution::load(service, claim("1"), configuration, CancellationToken::new())
                    .await?;
            tokio::time::timeout(Duration::from_secs(45), execution.run(kernel)).await??;
            Ok::<(), anyhow::Error>(())
        }
    };
    tokio::try_join!(
        run("alpha", "MANAGED_MODEL_ALPHA", true, alpha.clone()),
        run("beta", "PI_MODEL_BETA", false, beta.clone())
    )?;
    let captured = requests.lock().await;
    ensure!(captured.len() == 2, "unexpected model request count");
    for (route, service, cap, temperature) in
        [("alpha", alpha, 512, 0.2), ("beta", beta, 2048, 0.8)]
    {
        let request = captured.iter().find(|r| r["route"] == route).unwrap();
        ensure!(
            request["auth"] == format!("Bearer {route}-only-secret"),
            "credential binding mixed"
        );
        let body = &request["body"];
        ensure!(
            body["tools"][0]["parameters"]
                == json!({
                    "type":"object", "properties":{
                        "command":{"type":"string","minLength":1,"pattern":"^[a-z]"},
                        "seconds":{"type":"integer","minimum":0,"maximum":40,"default":0}
                    }, "required":["command"],"additionalProperties":false
                }),
            "declared tool schema constraints changed at native HTTP boundary"
        );
        ensure!(
            body["model"] == format!("model-{route}")
                && body["max_output_tokens"] == cap
                && body["temperature"] == temperature,
            "native parameters lost"
        );
        let prompt = body["input"].to_string();
        ensure!(
            prompt.contains(&format!("Managed {route} agent instructions."))
                && !prompt.contains("You are a coding agent running in the Codex CLI")
                && !prompt.contains("<environment_context>")
                && !prompt.contains("<permissions instructions>"),
            "managed system must replace the default Codex persona"
        );
        if route == "alpha" {
            ensure!(
                body["reasoning"]["effort"] == "low",
                "reasoning effort lost"
            );
        } else {
            ensure!(
                body["reasoning"].is_null(),
                "reasoning sent to nonreasoning model"
            );
        }
        let durable = service.durable.lock().await;
        ensure!(
            durable.bundle["terminal"]["status"] == "succeeded",
            "native model did not finish"
        );
        ensure!(
            !serde_json::to_string(&*durable)?.contains("only-secret"),
            "credential leaked into durable state"
        );
        let dispatch = durable.bundle["modelUpdates"]
            .as_array()
            .unwrap()
            .iter()
            .find(|v| v["phase"] == "dispatch")
            .unwrap();
        let reference = &dispatch["requestRef"];
        // The budget gate must observe the same mapped request as the model endpoint.
        let request_object = &durable.objects[reference["sha256"].as_str().unwrap()];
        use base64::{Engine, engine::general_purpose::STANDARD};
        let budget: Value = serde_json::from_slice(
            &STANDARD.decode(request_object["dataBase64"].as_str().unwrap())?,
        )?;
        ensure!(
            budget == *body,
            "budget request differs from dispatched request"
        );
    }
    server.abort();
    Ok(())
}

async fn model(
    State(requests): State<Arc<Mutex<Vec<Value>>>>,
    Json(request): Json<Value>,
) -> ([(&'static str, &'static str); 1], String) {
    let mut requests = requests.lock().await;
    requests.push(request);
    let items = if requests.len() == 1 {
        vec![
            json!({"type":"message","id":"message-intermediate","role":"assistant","content":[{"type":"output_text","text":"Checking both tools."}]}),
            json!({"type":"function_call","id":"fc_a","name":"hands_execute","call_id":"call_a","arguments":"{\"command\":\"echo A\"}"}),
            json!({"type":"function_call","id":"fc_b","name":"hands_execute","call_id":"call_b","arguments":"{\"command\":\"echo B\"}"}),
        ]
    } else {
        vec![
            json!({"type":"message","id":"message-final","role":"assistant","content":[{"type":"output_text","text":"Both original results received."}]}),
        ]
    };
    let mut events = vec![json!({"type":"response.created","response":{"id":"response-1"}})];
    for item in &items {
        events.push(json!({"type":"response.output_item.done","item":item}));
    }
    events.push(json!({"type":"response.completed","response":{"id":"response-1","output":items,"usage":{"input_tokens":10,"output_tokens":10,"total_tokens":20}}}));
    (
        [("content-type", "text/event-stream")],
        events
            .into_iter()
            .map(|e| format!("data: {e}\n\n"))
            .collect(),
    )
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn rejected_native_tool_response_settles_usage_without_dispatch_or_replay() -> Result<()> {
    let requests = Arc::new(Mutex::new(0));
    let captured = requests.clone();
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let base = format!("http://{}/v1", listener.local_addr()?);
    let app = Router::new().route(
        "/v1/responses",
        post(move || {
            let captured = captured.clone();
            async move {
                *captured.lock().await += 1;
                // A leading space is not an authorized alias. Its provider usage is still real.
                let item = json!({"type":"function_call","id":"invalid-tool","name":" hands_execute","call_id":"invalid-call","arguments":"{\"command\":\"echo prohibited\"}"});
                let body = [
                    json!({"type":"response.created","response":{"id":"invalid-response"}}),
                    json!({"type":"response.output_item.done","item":item}),
                    json!({"type":"response.completed","response":{"id":"invalid-response","output":[item],"usage":{"input_tokens":2237,"output_tokens":166,"total_tokens":2403}}}),
                ].iter().map(|e| format!("data: {e}\n\n")).collect::<String>();
                ([("content-type", "text/event-stream")], body)
            }
        }),
    );
    let server = tokio::spawn(async move { axum::serve(listener, app).await });
    let kernel = CodexKernel::new(base, "test-only".into(), "openai".into());
    let service = Arc::new(Service::default());
    let execution = Execution::load(
        service.clone(),
        claim("1"),
        config(),
        CancellationToken::new(),
    )
    .await?;
    // The native client may report the rejected HTTP response, but the fenced failure must
    // already contain its settlement. Recovery must not authorize another model dispatch.
    let _native_result =
        tokio::time::timeout(Duration::from_secs(45), execution.run(&kernel)).await?;
    ensure!(
        *requests.lock().await == 1,
        "rejected provider response was replayed"
    );
    let state = service.durable.lock().await;
    ensure!(
        state.bundle["terminal"]["status"] == "failed",
        "missing durable failure"
    );
    ensure!(
        state.bundle["toolOperations"]
            .as_array()
            .unwrap()
            .is_empty(),
        "invalid tool reached Hands"
    );
    let completions = state
        .commits
        .iter()
        .filter(|c| c["changes"]["terminal"]["status"] == "failed")
        .collect::<Vec<_>>();
    ensure!(
        completions.len() == 1,
        "terminal failure not committed exactly once"
    );
    let update = &completions[0]["changes"]["modelUpdates"][0];
    ensure!(
        update["phase"] == "complete" && update["status"] == "failed",
        "usage not atomic with terminal failure"
    );
    ensure!(
        update["usage"]["inputTokens"] == "2237"
            && update["usage"]["outputTokens"] == "166"
            && update["usageUnknown"].is_null(),
        "known usage lost"
    );
    drop(state);
    server.abort();
    Ok(())
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn large_native_public_message_uses_objects_for_facts_and_terminal_output() -> Result<()> {
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let base = format!("http://{}/v1", listener.local_addr()?);
    let text = "x".repeat(300_000);
    let item = json!({"type":"message","id":"large-message","role":"assistant","content":[{"type":"output_text","text":text}]});
    let body = [
        json!({"type":"response.created","response":{"id":"large-response"}}),
        json!({"type":"response.output_item.done","item":item}),
        json!({"type":"response.completed","response":{"id":"large-response","output":[item]}}),
    ]
    .iter()
    .map(|event| format!("data: {event}\n\n"))
    .collect::<String>();
    let app = Router::new().route(
        "/v1/responses",
        post(move || {
            let body = body.clone();
            async move { ([("content-type", "text/event-stream")], body) }
        }),
    );
    let server = tokio::spawn(async move { axum::serve(listener, app).await });
    let kernel = CodexKernel::new(base, "test-only".into(), "openai".into());
    let service = Arc::new(Service::default());
    let mut configuration = config();
    configuration.tools.clear();
    let execution = Execution::load(
        service.clone(),
        claim("1"),
        configuration,
        CancellationToken::new(),
    )
    .await?;
    tokio::time::timeout(Duration::from_secs(45), execution.run(&kernel)).await??;
    let (fact, terminal) = {
        let state = service.durable.lock().await;
        ensure!(
            state
                .commits
                .iter()
                .all(|commit| serde_json::to_vec(commit).unwrap().len() < 16 * 1024),
            "public text expanded commit metadata"
        );
        let facts = state
            .commits
            .iter()
            .flat_map(|commit| commit["publicFacts"].as_array().into_iter().flatten())
            .collect::<Vec<_>>();
        ensure!(facts.len() == 1, "message not published exactly once");
        (facts[0].clone(), state.bundle["terminal"].clone())
    };
    let message: Value = serde_json::from_slice(
        &client::read(
            service.as_ref(),
            &execution.claim.scope,
            &serde_json::from_value(fact["content"]["ref"].clone())?,
        )
        .await?,
    )?;
    let output: Value = serde_json::from_slice(
        &client::read(
            service.as_ref(),
            &execution.claim.scope,
            &serde_json::from_value(terminal["output"]["ref"].clone())?,
        )
        .await?,
    )?;
    ensure!(
        message["item"]["message_id"] == terminal["messageId"],
        "final message identity changed"
    );
    ensure!(
        message["item"]["content"][0]["text"] == text && output[0]["text"] == text,
        "large message content changed"
    );
    server.abort();
    Ok(())
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn native_core_suspends_and_resumes_original_results_under_new_owner() -> Result<()> {
    let requests = Arc::new(Mutex::new(Vec::new()));
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let base = format!("http://{}/v1", listener.local_addr()?);
    let app = Router::new()
        .route("/v1/responses", post(model))
        .with_state(requests.clone());
    let server = tokio::spawn(async move { axum::serve(listener, app).await });
    let kernel = CodexKernel::new(base, "test-only".into(), "openai".into());
    let service = Arc::new(Service::default());
    service.durable.lock().await.lose_commit = true;
    let first = Execution::load(
        service.clone(),
        claim("1"),
        config(),
        CancellationToken::new(),
    )
    .await?;
    tokio::time::timeout(Duration::from_secs(45), first.run(&kernel)).await??;
    drop(first);
    let native_turn = {
        let mut durable = service.durable.lock().await;
        ensure!(
            durable.bundle["toolOperations"].as_array().unwrap().len() == 2,
            "expected two Hands operations"
        );
        ensure!(
            durable.bundle["resultApplications"]
                .as_array()
                .unwrap()
                .len()
                == 1,
            "A should be applied before suspension"
        );
        ensure!(
            durable.commits.last().unwrap()["changes"]["suspension"]["pendingTaskIds"]
                .as_array()
                .unwrap()
                .len()
                == 1,
            "only B pending"
        );
        let state = &durable.objects[durable.bundle["nativeState"]["checkpointRef"]["sha256"]
            .as_str()
            .unwrap()];
        use base64::{Engine, engine::general_purpose::STANDARD};
        let snapshot: Value =
            serde_json::from_slice(&STANDARD.decode(state["dataBase64"].as_str().unwrap())?)?;
        ensure!(
            !snapshot.to_string().contains("cancelled before receiving"),
            "suspension cleanup leaked into checkpoint"
        );
        durable.fence = serde_json::to_value(claim("2").fence)?;
        durable.finish_b = true;
        snapshot["turnId"].clone()
    };
    let second = Execution::load(
        service.clone(),
        claim("2"),
        config(),
        CancellationToken::new(),
    )
    .await?;
    tokio::time::timeout(Duration::from_secs(45), second.run(&kernel)).await??;
    let durable = service.durable.lock().await;
    ensure!(
        durable.bundle["terminal"]["status"] == "succeeded",
        "Turn did not complete"
    );
    ensure!(
        durable.bundle["toolOperations"].as_array().unwrap().len() == 2,
        "external operation redispatched"
    );
    ensure!(
        durable.bundle["resultApplications"]
            .as_array()
            .unwrap()
            .len()
            == 2,
        "result was not applied once"
    );
    let captured = requests.lock().await;
    ensure!(captured.len() == 2, "unexpected model generations");
    let messages = durable
        .commits
        .iter()
        .flat_map(|commit| commit["publicFacts"].as_array().into_iter().flatten())
        .filter(|fact| fact["type"] == "item.completed")
        .collect::<Vec<_>>();
    ensure!(
        messages.len() == 2,
        "native intermediate/final messages must be published exactly once"
    );
    ensure!(
        messages[0]["content"]["inline"]["item"]["content"][0]["text"] == "Checking both tools.",
        "intermediate message missing"
    );
    ensure!(
        messages[1]["content"]["inline"]["item"]["message_id"]
            == durable.bundle["terminal"]["messageId"],
        "final message identity changed"
    );
    let usage = durable.bundle["modelUpdates"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|update| update["phase"] == "complete")
        .collect::<Vec<_>>();
    ensure!(
        usage.len() == 2
            && usage
                .iter()
                .all(|update| update["usage"]["inputTokens"] == "10"
                    && update["usageUnknown"].is_null()),
        "native usage not recorded"
    );
    let tools = captured[0]["tools"].as_array().unwrap();
    ensure!(
        tools.len() == 1 && tools[0]["type"] == "function" && tools[0]["name"] == "hands_execute",
        "model tool catalog escaped the Hands allowlist"
    );
    let input = captured[1]["input"].as_array().unwrap();
    for id in ["call_a", "call_b"] {
        let outputs = input
            .iter()
            .filter(|v| v["type"] == "function_call_output" && v["call_id"] == id)
            .collect::<Vec<_>>();
        ensure!(
            outputs.len() == 1 && outputs[0].to_string().contains("original Hands result"),
            "original result not in model context"
        );
    }
    ensure!(!native_turn.is_null(), "native Turn ID missing");
    use base64::{Engine, engine::general_purpose::STANDARD};
    let final_object = &durable.objects[durable.bundle["nativeState"]["checkpointRef"]["sha256"]
        .as_str()
        .unwrap()];
    let final_snapshot: Value =
        serde_json::from_slice(&STANDARD.decode(final_object["dataBase64"].as_str().unwrap())?)?;
    ensure!(
        final_snapshot["turnId"] == native_turn,
        "recovery started a replacement native Turn"
    );
    // Export an opt-in protocol transcript for independent JSON-schema validation.
    if let Ok(path) = std::env::var("MANAGED_CODEX_TRANSCRIPT") {
        std::fs::write(path, serde_json::to_vec_pretty(&durable.transcript)?)?;
    }
    server.abort();
    Ok(())
}

fn committed_snapshot(durable: &Durable) -> Result<Value> {
    use base64::{Engine, engine::general_purpose::STANDARD};
    let object = &durable.objects[durable.bundle["nativeState"]["checkpointRef"]["sha256"]
        .as_str()
        .unwrap()];
    Ok(serde_json::from_slice(
        &STANDARD.decode(object["dataBase64"].as_str().unwrap())?,
    )?)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn next_user_turn_preserves_history_after_success_or_cancellation() -> Result<()> {
    for cancel_previous in [false, true] {
        let requests = Arc::new(Mutex::new(Vec::new()));
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let base = format!("http://{}/v1", listener.local_addr()?);
        let app = Router::new()
            .route("/v1/responses", post(model))
            .with_state(requests.clone());
        let server = tokio::spawn(async move { axum::serve(listener, app).await });
        let kernel = CodexKernel::new(base, "test-only".into(), "openai".into());
        let service = Arc::new(Service::default());
        for epoch in ["1", "2"] {
            {
                let mut state = service.durable.lock().await;
                state.fence = serde_json::to_value(claim(epoch).fence)?;
                if epoch == "2" {
                    if cancel_previous {
                        state.bundle["pendingInputs"] =
                            json!([{"inputId":"cancel-original", "type":"cancel"}]);
                    } else {
                        state.finish_b = true;
                    }
                }
            }
            let execution = Execution::load(
                service.clone(),
                claim(epoch),
                config(),
                CancellationToken::new(),
            )
            .await?;
            tokio::time::timeout(Duration::from_secs(45), execution.run(&kernel)).await??;
        }
        let mut next_claim = claim("3");
        next_claim.turn_id = "next-platform-turn".into();
        let previous = {
            let mut state = service.durable.lock().await;
            ensure!(
                state.bundle["terminal"]["status"]
                    == if cancel_previous {
                        "cancelled"
                    } else {
                        "succeeded"
                    },
                "first Turn did not settle"
            );
            let snapshot = committed_snapshot(&state)?;
            // Service keeps a Session head, but returns only this Turn's applications and tasks.
            for field in [
                "toolOperations",
                "resultApplications",
                "acceptedResults",
                "modelUpdates",
                "interactionUpdates",
                "inputApplications",
                "pendingInputs",
            ] {
                state.bundle[field] = json!([]);
            }
            state.bundle.as_object_mut().unwrap().remove("terminal");
            state.fence = serde_json::to_value(&next_claim.fence)?;
            snapshot
        };
        let mut configuration = config();
        configuration.prompt =
            "This is a distinct second user message. Keep the previous conversation.".into();
        let execution = Execution::load(
            service.clone(),
            next_claim.clone(),
            configuration,
            CancellationToken::new(),
        )
        .await?;
        tokio::time::timeout(Duration::from_secs(45), execution.run(&kernel)).await??;
        let state = service.durable.lock().await;
        let snapshot = committed_snapshot(&state)?;
        ensure!(
            state.bundle["terminal"]["status"] == "succeeded",
            "next Turn did not finish"
        );
        ensure!(
            snapshot["threadId"] == previous["threadId"],
            "Session history was reset"
        );
        ensure!(
            snapshot["turnId"] != previous["turnId"],
            "new user input recovered the old native Turn"
        );
        ensure!(
            snapshot["platformTurnId"] == next_claim.turn_id,
            "native Turn binding missing"
        );
        ensure!(
            state.bundle["toolOperations"] == json!([]),
            "prior Turn tool was redispatched"
        );
        let requests = requests.lock().await;
        ensure!(
            requests.len() == if cancel_previous { 2 } else { 3 },
            "unexpected model calls"
        );
        let context = requests.last().unwrap()["input"].to_string();
        ensure!(
            context.contains("distinct second user message")
                && context.contains("original Hands result call_a"),
            "second Turn lost its prompt or original history"
        );
        if cancel_previous {
            ensure!(
                context.contains("Previous platform Turn ended"),
                "cancelled pending call not closed in history"
            );
        }
        server.abort();
    }
    Ok(())
}

/// Runs only as a child of the crash-recovery test. No environment means no work.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn native_process_phase() -> Result<()> {
    let Ok(path) = std::env::var("MANAGED_TEST_DURABLE_PATH") else {
        return Ok(());
    };
    let second = std::env::var("MANAGED_TEST_PHASE").as_deref() == Ok("2");
    let durable: Durable = serde_json::from_slice(&std::fs::read(&path)?)?;
    let service = Arc::new(Service {
        durable: Mutex::new(durable),
        disk: Some(path.into()),
        stop_after_application: !second,
        tool_content: None,
    });
    let kernel = CodexKernel::new(
        std::env::var("MANAGED_TEST_MODEL_URL")?,
        "test-only".into(),
        "openai".into(),
    );
    let execution = Execution::load(
        service,
        claim(if second { "2" } else { "1" }),
        config(),
        CancellationToken::new(),
    )
    .await?;
    execution.run(&kernel).await
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn sigkill_after_result_commit_recovers_in_a_new_process() -> Result<()> {
    let requests = Arc::new(Mutex::new(Vec::new()));
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let base = format!("http://{}/v1", listener.local_addr()?);
    let app = Router::new()
        .route("/v1/responses", post(model))
        .with_state(requests.clone());
    let server = tokio::spawn(async move { axum::serve(listener, app).await });
    let temp = tempfile::tempdir()?;
    let path = temp.path().join("session-service.json");
    std::fs::write(&path, serde_json::to_vec(&Durable::default())?)?;
    let spawn = |phase: &str| {
        let mut command = tokio::process::Command::new(std::env::current_exe().unwrap());
        command
            .args(["--exact", "native_process_phase", "--nocapture"])
            .env("MANAGED_TEST_DURABLE_PATH", &path)
            .env("MANAGED_TEST_MODEL_URL", &base)
            .env("MANAGED_TEST_PHASE", phase)
            .env("RUST_MIN_STACK", "16777216")
            .kill_on_drop(true)
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped());
        command.spawn()
    };
    let mut first = spawn("1")?;
    let mut durable = tokio::time::timeout(Duration::from_secs(45), async {
        loop {
            if let Some(status) = first.try_wait()? {
                anyhow::bail!("first child exited before crash point: {status}");
            }
            let durable: Durable = serde_json::from_slice(&std::fs::read(&path)?)?;
            if durable.bundle["resultApplications"]
                .as_array()
                .unwrap()
                .len()
                == 1
            {
                break Ok::<_, anyhow::Error>(durable);
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    })
    .await??;
    first.start_kill()?;
    let status = first.wait().await?;
    ensure!(!status.success(), "crash process was not killed");
    durable.fence = serde_json::to_value(claim("2").fence)?;
    durable.finish_b = true;
    std::fs::write(&path, serde_json::to_vec(&durable)?)?;
    let output =
        tokio::time::timeout(Duration::from_secs(45), spawn("2")?.wait_with_output()).await??;
    ensure!(
        output.status.success(),
        "second child failed: {}",
        String::from_utf8_lossy(&output.stdout)
    );
    let durable: Durable = serde_json::from_slice(&std::fs::read(path)?)?;
    ensure!(
        durable.bundle["terminal"]["status"] == "succeeded",
        "recovered process did not finish"
    );
    ensure!(
        durable.bundle["toolOperations"].as_array().unwrap().len() == 2,
        "Hands task replayed after SIGKILL"
    );
    ensure!(
        durable.bundle["resultApplications"]
            .as_array()
            .unwrap()
            .len()
            == 2,
        "result application replayed after SIGKILL"
    );
    ensure!(
        requests.lock().await.len() == 2,
        "unexpected model retry after committed result"
    );
    server.abort();
    Ok(())
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn native_text_preview_precedes_response_completion_and_matches_durable_identity()
-> Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let base = format!("http://{}/v1", listener.local_addr()?);
    let service = Arc::new(Service::default());
    let peer = service.clone();
    let server = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await?;
        let mut input = Vec::new();
        loop {
            let mut bytes = [0; 4096];
            let count = socket.read(&mut bytes).await?;
            ensure!(count > 0, "request closed early");
            input.extend_from_slice(&bytes[..count]);
            if let Some(end) = input.windows(4).position(|part| part == b"\r\n\r\n") {
                let headers = std::str::from_utf8(&input[..end])?.to_ascii_lowercase();
                let length = headers
                    .lines()
                    .find_map(|line| line.strip_prefix("content-length:"))
                    .unwrap()
                    .trim()
                    .parse::<usize>()?;
                if input.len() >= end + 4 + length {
                    break;
                }
            }
        }
        socket.write_all(b"HTTP/1.1 200 OK\r\ncontent-type: text/event-stream\r\ntransfer-encoding: chunked\r\nconnection: close\r\n\r\n").await?;
        let text = "你好🙂 live response";
        let prefix = [
            json!({"type":"response.created","response":{"id":"stream-response"}}),
            json!({"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"stream-message","role":"assistant","content":[]}}),
            json!({"type":"response.content_part.added","item_id":"stream-message","output_index":0,"content_index":0,"part":{"type":"output_text","text":""}}),
            json!({"type":"response.reasoning_text.delta","delta":"private reasoning"}),
            json!({"type":"response.output_text.delta","item_id":"stream-message","output_index":0,"content_index":0,"delta":text}),
        ].iter().map(|event|format!("data: {event}\r\n\r\n")).collect::<String>();
        socket
            .write_all(format!("{:x}\r\n{prefix}\r\n", prefix.len()).as_bytes())
            .await?;
        tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                let state = peer.durable.lock().await;
                if state
                    .transcript
                    .iter()
                    .any(|entry| entry["operation"] == "previews/publish")
                {
                    ensure!(
                        !state.bundle["modelUpdates"]
                            .as_array()
                            .unwrap()
                            .iter()
                            .any(|update| update["phase"] == "complete"),
                        "model completed before delta"
                    );
                    break Ok::<(), anyhow::Error>(());
                }
                drop(state);
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await??;
        let item = json!({"type":"message","id":"stream-message","role":"assistant","content":[{"type":"output_text","text":text}]});
        let suffix = [
            json!({"type":"response.output_item.done","output_index":0,"item":item}),
            json!({"type":"response.completed","response":{"id":"stream-response","output":[item]}}),
        ].iter().map(|event|format!("data: {event}\r\n\r\n")).collect::<String>();
        socket
            .write_all(format!("{:x}\r\n{suffix}\r\n0\r\n\r\n", suffix.len()).as_bytes())
            .await?;
        Ok::<(), anyhow::Error>(())
    });
    let kernel = CodexKernel::new(base, "fixture".into(), "openai".into())
        .with_credential_resolver(|name| {
            (name == "MANAGED_MODEL_COMPACT").then(|| "fixture".into())
        });
    let mut configuration = config();
    configuration.tools.clear();
    let execution = Execution::load(
        service.clone(),
        claim("1"),
        configuration,
        CancellationToken::new(),
    )
    .await?;
    let result = tokio::time::timeout(Duration::from_secs(30), execution.run(&kernel)).await?;
    server.await??;
    result?;
    let state = service.durable.lock().await;
    let batches = state
        .transcript
        .iter()
        .filter(|entry| entry["operation"] == "previews/publish")
        .collect::<Vec<_>>();
    ensure!(batches.len() == 1, "expected one preview batch");
    ensure!(
        batches[0]["request"]["chunks"] == json!(["你好🙂 live response"]),
        "private or missing preview text"
    );
    ensure!(
        batches[0]["request"]["messageId"] == state.bundle["terminal"]["messageId"],
        "preview and native final identities differ"
    );
    ensure!(
        state.bundle["terminal"]["status"] == "succeeded",
        "native Turn did not complete"
    );
    Ok(())
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn native_compaction_and_reasoning_both_cross_the_persistent_model_gate() -> Result<()> {
    let requests = Arc::new(Mutex::new(Vec::new()));
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let base = format!("http://{}/v1", listener.local_addr()?);
    let captured = requests.clone();
    let app = Router::new().route("/v1/responses", post(move |Json(body): Json<Value>| {
        let captured = captured.clone();
        async move {
            let mut rows = captured.lock().await;
            let id = format!("response-{}", rows.len());
            rows.push(body);
            let item = json!({"type":"message","id":format!("{id}-message"),"role":"assistant",
                "content":[{"type":"output_text","text":"The user requested a concise acknowledgement. Acknowledged."}]});
            let events = [json!({"type":"response.created","response":{"id":id}}),
                json!({"type":"response.output_item.done","item":item}),
                json!({"type":"response.completed","response":{"id":id,"output":[item],
                    "usage":{"input_tokens":if rows.len()==1 {1500} else {20},"output_tokens":10,"total_tokens":if rows.len()==1 {1510} else {30}}}})];
            ([("content-type","text/event-stream")],events.iter().map(|e|format!("data: {e}\n\n")).collect::<String>())
        }
    }));
    let server = tokio::spawn(async move { axum::serve(listener, app).await });
    let service = Arc::new(Service::default());
    let mut configuration = config();
    configuration.tools.clear();
    configuration.prompt = "Please acknowledge. ".repeat(3000);
    configuration.model = serde_json::from_value(json!({"provider":"openai","id":"test-model",
        "api":"openai-responses","baseUrl":base,"credentialEnv":"MANAGED_MODEL_COMPACT","contextWindow":1024,"outputLimit":256}))?;
    let kernel = CodexKernel::new(base, "fixture".into(), "openai".into())
        .with_credential_resolver(|name| {
            (name == "MANAGED_MODEL_COMPACT").then(|| "fixture".into())
        });
    let execution = Execution::load(
        service.clone(),
        claim("1"),
        configuration.clone(),
        CancellationToken::new(),
    )
    .await?;
    tokio::time::timeout(Duration::from_secs(60), execution.run(&kernel)).await??;
    let mut second = claim("2");
    second.turn_id = "second-platform-turn".into();
    {
        let mut ledger = service.durable.lock().await;
        for field in [
            "toolOperations",
            "resultApplications",
            "acceptedResults",
            "modelUpdates",
            "interactionUpdates",
            "inputApplications",
            "pendingInputs",
        ] {
            ledger.bundle[field] = json!([]);
        }
        ledger.bundle.as_object_mut().unwrap().remove("terminal");
        ledger.fence = serde_json::to_value(&second.fence)?;
    }
    configuration.prompt = "Acknowledge again.".into();
    let execution = Execution::load(
        service.clone(),
        second,
        configuration,
        CancellationToken::new(),
    )
    .await?;
    let outcome = tokio::time::timeout(Duration::from_secs(60), execution.run(&kernel)).await?;
    server.abort();
    outcome?;
    let rows = requests.lock().await;
    let ledger = service.durable.lock().await;
    let serialized = serde_json::to_string(&ledger.commits)?;
    if std::env::var("MANAGED_AGENTS_EVALUATION_CONTEXT_MODE").as_deref() == Ok("short") {
        ensure!(
            rows.len() == 2 && !serialized.contains("compaction"),
            "short baseline compacted history"
        );
    } else {
        ensure!(
            rows.len() >= 3 && serialized.contains("compaction"),
            "native compaction was not durably classified"
        );
    }
    let updates = ledger
        .commits
        .iter()
        .flat_map(|commit| {
            commit["changes"]["modelUpdates"]
                .as_array()
                .into_iter()
                .flatten()
        })
        .collect::<Vec<_>>();
    ensure!(
        updates.iter().filter(|u| u["phase"] == "dispatch").count() == rows.len(),
        "an inference bypassed admission"
    );
    ensure!(
        updates
            .iter()
            .filter(|u| u["phase"] == "complete" && u["usage"].is_object())
            .count()
            == rows.len(),
        "auxiliary usage was not persisted"
    );
    Ok(())
}

#[test]
fn short_context_baseline_disables_native_compaction_in_an_isolated_process() -> Result<()> {
    let output = std::process::Command::new(std::env::current_exe()?)
        .args([
            "--exact",
            "native_compaction_and_reasoning_both_cross_the_persistent_model_gate",
        ])
        .env("MANAGED_AGENTS_EVALUATION_CONTEXT_MODE", "short")
        .output()?;
    ensure!(
        output.status.success(),
        "short context fixture failed: {}",
        String::from_utf8_lossy(&output.stdout)
    );
    Ok(())
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn aligned_tool_results_reach_the_model_without_native_truncation() -> Result<()> {
    if std::env::var("MANAGED_CODEX_TOOL_FIXTURE").is_err() {
        let output = std::process::Command::new(std::env::current_exe()?)
            .args([
                "--exact",
                "aligned_tool_results_reach_the_model_without_native_truncation",
            ])
            .env("MANAGED_CODEX_TOOL_FIXTURE", "1")
            .env("MANAGED_AGENTS_EVALUATION_CONTEXT_MODE", "short")
            .env("MANAGED_AGENTS_EVALUATION_TOOL_ENVELOPE", "text-json-v1")
            .output()?;
        ensure!(
            output.status.success(),
            "tool fixture failed: {} {}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        return Ok(());
    }
    for expected in [
        json!({"content":[{"type":"text","text":"x".repeat(50_000)}],
            "structuredContent":{"nested":[1,{"empty":[]}]},"isError":false}),
        json!({"content":[],"structuredContent":{},"isError":false}),
        json!({"content":[{"type":"text","text":"explicit failure"}],"isError":true}),
    ] {
        let requests = Arc::new(Mutex::new(Vec::new()));
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let base = format!("http://{}/v1", listener.local_addr()?);
        let app = Router::new()
            .route("/v1/responses", post(model))
            .with_state(requests.clone());
        let server = tokio::spawn(async move { axum::serve(listener, app).await });
        let kernel = CodexKernel::new(base, "fixture".into(), "openai".into());
        let service = Arc::new(Service {
            tool_content: Some(expected.clone()),
            ..Default::default()
        });
        service.durable.lock().await.finish_b = true;
        let execution = Execution::load(
            service.clone(),
            claim("1"),
            config(),
            CancellationToken::new(),
        )
        .await?;
        tokio::time::timeout(Duration::from_secs(45), execution.run(&kernel)).await??;
        server.abort();
        let rows = requests.lock().await;
        ensure!(rows.len() == 2, "unexpected extra inference");
        let outputs = rows[1]["input"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|v| v["type"] == "function_call_output")
            .collect::<Vec<_>>();
        ensure!(outputs.len() == 2, "missing original tool outputs");
        for output in outputs {
            let value: Value = serde_json::from_str(output["output"].as_str().unwrap())?;
            ensure!(
                value == expected,
                "native tool output was wrapped or truncated"
            );
        }
    }
    Ok(())
}
