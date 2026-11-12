use crate::client::Transport;
use serde_json::Value;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

const MAX_PENDING: usize = 64 * 1024;

/// Ephemeral text delivery. Queue overflow, rejection and timeouts only stop this lane.
pub struct TextPreview {
    sender: Option<mpsc::Sender<String>>,
    bytes: Arc<AtomicUsize>,
    stop: CancellationToken,
    task: JoinHandle<()>,
}

impl TextPreview {
    pub fn new(
        client: Arc<dyn Transport>,
        mut identity: Value,
        model_call: &str,
        message: &str,
        cancel: &CancellationToken,
    ) -> Self {
        identity["modelCallId"] = model_call.into();
        let (sender, mut receiver) = mpsc::channel::<String>(256);
        let bytes = Arc::new(AtomicUsize::new(0));
        let queued = bytes.clone();
        let stop = cancel.child_token();
        let stopped = stop.clone();
        let message = message.to_owned();
        let task = tokio::spawn(async move {
            let work = async {
                let interest = tokio::time::timeout(
                    Duration::from_millis(250),
                    client.post("previews/interest", &identity),
                )
                .await
                .ok()?
                .ok()?;
                if interest["interested"] != true {
                    return None;
                }
                identity["messageId"] = message.into();
                let mut sequence = 0_u64;
                while let Some(first) = receiver.recv().await {
                    let mut chunks = vec![first];
                    // Bound even worst-case JSON escaping below the 64 KiB wire limit.
                    while chunks.len() < 8 {
                        match receiver.try_recv() {
                            Ok(chunk) => chunks.push(chunk),
                            Err(_) => break,
                        }
                    }
                    queued.fetch_sub(chunks.iter().map(String::len).sum(), Ordering::Relaxed);
                    identity["sequence"] = sequence.to_string().into();
                    identity["chunks"] = serde_json::to_value(&chunks).ok()?;
                    let response = tokio::time::timeout(
                        Duration::from_millis(250),
                        client.post("previews/publish", &identity),
                    )
                    .await
                    .ok()?
                    .ok()?;
                    if response["accepted"] != true {
                        return None;
                    }
                    sequence += chunks.len() as u64 + u64::from(sequence == 0);
                }
                Some(())
            };
            tokio::select! { _ = work => {}, _ = stopped.cancelled() => {} }
            stopped.cancel();
        });
        Self {
            sender: Some(sender),
            bytes,
            stop,
            task,
        }
    }

    pub fn accept(&mut self, text: &str) {
        if self.stop.is_cancelled() || text.is_empty() {
            return;
        }
        if self
            .bytes
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |queued| {
                queued
                    .checked_add(text.len())
                    .filter(|sum| *sum <= MAX_PENDING)
            })
            .is_err()
        {
            self.stop.cancel();
            return;
        }
        let mut chars = text.chars().peekable();
        while chars.peek().is_some() {
            let chunk: String = chars.by_ref().take(1024).collect();
            if self
                .sender
                .as_ref()
                .is_none_or(|sender| sender.try_send(chunk).is_err())
            {
                self.stop.cancel();
                return;
            }
        }
    }

    pub async fn finish(mut self) {
        self.sender.take();
        let _ = tokio::time::timeout(Duration::from_millis(200), &mut self.task).await;
        self.stop.cancel();
    }
}

impl Drop for TextPreview {
    fn drop(&mut self) {
        self.stop.cancel();
        self.task.abort();
    }
}
