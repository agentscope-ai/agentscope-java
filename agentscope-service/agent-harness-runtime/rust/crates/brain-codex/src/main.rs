use anyhow::{Result, ensure};
use managed_adapter_codex::{CODEX_REVISION, CodexKernel};
use managed_brain_runtime::client::HttpTransport;
use managed_brain_runtime::worker::{WorkerConfig, run_worker};
use serde_json::json;
use std::sync::Arc;
use tokio_util::sync::CancellationToken;

fn main() -> Result<()> {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .thread_stack_size(16 * 1024 * 1024)
        .build()?
        .block_on(run())
}

async fn run() -> Result<()> {
    if std::env::args().any(|arg| arg == "--health-check") {
        reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(2))
            .build()?
            .get(format!(
                "http://127.0.0.1:{}/readyz",
                std::env::var("PORT").unwrap_or_else(|_| "8082".into())
            ))
            .send()
            .await?
            .error_for_status()?;
        return Ok(());
    }
    if std::env::args().any(|arg| arg == "--version") {
        println!(
            "{}",
            json!({"brain":"codex-rust","version":env!("CARGO_PKG_VERSION"),"codexRevision":CODEX_REVISION,"protocolVersion":"0.1.0"})
        );
        return Ok(());
    }
    tracing_subscriber::fmt()
        .json()
        .with_env_filter("managed_brain_runtime=info")
        .init();
    let required = |name: &str| -> Result<String> {
        let value = std::env::var(name).map_err(|_| anyhow::anyhow!("{name} is required"))?;
        ensure!(!value.is_empty(), "{name} must not be empty");
        Ok(value)
    };
    let client = Arc::new(HttpTransport::new(
        &required("SESSION_SERVICE_URL")?,
        required("MANAGED_AGENTS_INTERNAL_TOKEN")?,
    )?);
    let kernel = Arc::new(CodexKernel::new(
        std::env::var("CODEX_MODEL_BASE_URL")
            .unwrap_or_else(|_| "https://api.openai.com/v1".into()),
        std::env::var("CODEX_MODEL_API_KEY").unwrap_or_default(),
        std::env::var("CODEX_MODEL_PROVIDER").unwrap_or_else(|_| "openai".into()),
    ));
    let drain = CancellationToken::new();
    let stop = drain.clone();
    tokio::spawn(async move {
        #[cfg(unix)]
        {
            if let Ok(mut term) =
                tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            {
                tokio::select! {_ = term.recv() => {}, _ = tokio::signal::ctrl_c() => {}}
            }
        }
        #[cfg(not(unix))]
        let _ = tokio::signal::ctrl_c().await;
        stop.cancel();
    });
    run_worker(
        client,
        kernel,
        WorkerConfig {
            concurrency: std::env::var("BRAIN_CONCURRENCY")
                .unwrap_or_else(|_| "4".into())
                .parse()?,
            inline_wait_ms: std::env::var("BRAIN_INLINE_WAIT_MS")
                .unwrap_or_else(|_| "1000".into())
                .parse()?,
            health_address: format!(
                "0.0.0.0:{}",
                std::env::var("PORT").unwrap_or_else(|_| "8082".into())
            ),
            instance_id: std::env::var("BRAIN_INSTANCE_ID")
                .unwrap_or_else(|_| "codex-brain".into()),
        },
        drain,
    )
    .await
}
