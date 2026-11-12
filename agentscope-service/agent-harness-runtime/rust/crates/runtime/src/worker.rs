use crate::client::{ServiceError, Transport};
use crate::execution::{Execution, run_owned};
use crate::kernel::Kernel;
use crate::protocol::{Claim, Owner, Scope, VERSION, scope_key};
use anyhow::{Result, ensure};
use axum::{Json, Router, extract::State, http::StatusCode, routing::get};
use serde::Deserialize;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::Duration;
use tokio::net::TcpListener;
use tokio::task::JoinSet;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

pub struct WorkerConfig {
    pub concurrency: usize,
    pub inline_wait_ms: u64,
    pub health_address: String,
    pub instance_id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Delivery {
    scope: Scope,
    turn_id: String,
    trigger: Value,
}

struct Health {
    drain: CancellationToken,
    connected: AtomicBool,
    active: AtomicUsize,
}

async fn ready(State(state): State<Arc<Health>>) -> (StatusCode, Json<Value>) {
    let ready = !state.drain.is_cancelled() && state.connected.load(Ordering::SeqCst);
    (
        if ready {
            StatusCode::OK
        } else {
            StatusCode::SERVICE_UNAVAILABLE
        },
        Json(json!({"harness":"codex","active":state.active.load(Ordering::SeqCst),"ready":ready})),
    )
}

pub async fn run_worker(
    client: Arc<dyn Transport>,
    kernel: Arc<dyn Kernel>,
    config: WorkerConfig,
    drain: CancellationToken,
) -> Result<()> {
    ensure!(
        (1..=64).contains(&config.concurrency) && config.inline_wait_ms <= 600_000,
        "worker limits invalid"
    );
    let health = Arc::new(Health {
        drain: drain.clone(),
        connected: AtomicBool::new(false),
        active: AtomicUsize::new(0),
    });
    let listener = TcpListener::bind(&config.health_address).await?;
    let app = Router::new()
        .route("/readyz", get(ready))
        .route("/healthz", get(|| async { StatusCode::OK }))
        .with_state(health.clone());
    let stop = drain.clone();
    let server = tokio::spawn(async move {
        axum::serve(listener, app)
            .with_graceful_shutdown(stop.cancelled_owned())
            .await
    });
    let owner = Owner {
        instance_id: config.instance_id,
        incarnation_id: Uuid::new_v4().to_string(),
    };
    let mut active = HashSet::new();
    let mut tasks = JoinSet::new();
    let mut task_scopes = HashMap::new();
    while !drain.is_cancelled() {
        while let Some(result) = tasks.try_join_next_with_id() {
            let id = match result {
                Ok((id, ())) => id,
                Err(error) => {
                    tracing::warn!(
                        event = "execution.join_failed",
                        "Execution task stopped unexpectedly"
                    );
                    error.id()
                }
            };
            if let Some(key) = task_scopes.remove(&id) {
                active.remove(&key);
            }
        }
        health.active.store(active.len(), Ordering::SeqCst);
        if active.len() < config.concurrency {
            let poll_request = json!({"protocolVersion":VERSION,"harnessType":"codex","limit":config.concurrency-active.len()});
            let page = tokio::select! {
                value = client.post("executions/next",&poll_request) => value,
                _ = drain.cancelled() => break,
            };
            let page = page.and_then(|value| {
                Ok(serde_json::from_value::<Vec<Delivery>>(
                    value["deliveries"].clone(),
                )?)
            });
            match page {
                Ok(deliveries) => {
                    health.connected.store(true, Ordering::SeqCst);
                    for delivery in deliveries
                        .into_iter()
                        .take(config.concurrency - active.len())
                    {
                        let key = scope_key(&delivery.scope);
                        if !active.insert(key.clone()) {
                            continue;
                        }
                        let client = client.clone();
                        let kernel = kernel.clone();
                        let drain = drain.clone();
                        let owner = owner.clone();
                        let task_key = key.clone();
                        let task = tasks.spawn(async move {
                            let result: Result<()> = async {
                                let request = json!({"protocolVersion":VERSION,"scope":delivery.scope,"turnId":delivery.turn_id,"trigger":delivery.trigger,"harnessType":"codex","owner":owner,"claimId":Uuid::new_v4().to_string()});
                                let claimed = match client.post("executions/claim",&request).await {
                                    Ok(claimed) => claimed,
                                    Err(error) if matches!(error.downcast_ref::<ServiceError>(),Some(ServiceError::Unconfirmed)) => client.post("executions/claim",&request).await?,
                                    Err(error) => return Err(error),
                                };
                                let claim: Claim = serde_json::from_value(claimed)?;
                                ensure!(claim.scope == delivery.scope && claim.turn_id == delivery.turn_id && claim.fence.owner == owner,"claim identity mismatch");
                                let configuration = client.post("executions/configuration",&json!({"protocolVersion":VERSION,"scope":claim.scope,"turnId":claim.turn_id,"fence":claim.fence})).await?;
                                let mut configuration = crate::configuration::decode(configuration, &claim.scope, &claim.turn_id, "codex")?;
                                configuration.inline_wait_ms.get_or_insert(config.inline_wait_ms);
                                let execution = Execution::load(client,claim,configuration,CancellationToken::new()).await?;
                                run_owned(execution,kernel,drain).await
                            }.await;
                            if result.is_err() { tracing::warn!(event="execution.retry",scope_key=%key,"Execution stopped; ownership and recovery remain with Session Service"); }
                        });
                        task_scopes.insert(task.id(), task_key);
                    }
                }
                Err(_) => {
                    health.connected.store(false, Ordering::SeqCst);
                    tracing::warn!(event = "worker.retry", "Session Service poll failed");
                }
            }
        }
        tokio::select! {_ = tokio::time::sleep(Duration::from_millis(500)) => {}, _ = drain.cancelled() => break}
    }
    drain.cancel();
    let _ = tokio::time::timeout(Duration::from_secs(10), async {
        while tasks.join_next().await.is_some() {}
    })
    .await;
    tasks.abort_all();
    server.await??;
    Ok(())
}
