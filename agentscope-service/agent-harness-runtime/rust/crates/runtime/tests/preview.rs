use anyhow::Result;
use async_trait::async_trait;
use managed_brain_runtime::client::Transport;
use managed_brain_runtime::preview::TextPreview;
use serde_json::{Value, json};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Mutex;
use tokio_util::sync::CancellationToken;

struct Peer {
    mode: u8,
    requests: Mutex<Vec<Value>>,
}
#[async_trait]
impl Transport for Peer {
    async fn post(&self, operation: &str, request: &Value) -> Result<Value> {
        self.requests
            .lock()
            .await
            .push(json!({"operation":operation,"request":request}));
        if operation == "previews/interest" {
            return Ok(json!({"interested":self.mode != 1}));
        }
        if self.mode == 2 {
            anyhow::bail!("preview unavailable");
        }
        if self.mode == 3 {
            std::future::pending::<()>().await;
        }
        Ok(json!({"accepted":true}))
    }
}

#[tokio::test]
async fn bounded_unicode_batches_preserve_frame_sequence() {
    let peer = Arc::new(Peer {
        mode: 0,
        requests: Mutex::new(vec![]),
    });
    let cancel = CancellationToken::new();
    let mut lane = TextPreview::new(peer.clone(), json!({}), "call", "message", &cancel);
    let text = "🙂你".repeat(1500) + &"\u{1}".repeat(9000);
    lane.accept(&text);
    lane.finish().await;
    let requests = peer.requests.lock().await;
    let mut sequence = 0;
    let mut received = String::new();
    for record in requests
        .iter()
        .filter(|v| v["operation"] == "previews/publish")
    {
        let request = &record["request"];
        assert_eq!(request["sequence"], sequence.to_string());
        assert!(serde_json::to_vec(request).unwrap().len() < 64 * 1024);
        let chunks = request["chunks"].as_array().unwrap();
        sequence += chunks.len() + usize::from(sequence == 0);
        for chunk in chunks {
            assert!(chunk.as_str().unwrap().chars().count() <= 1024);
            received.push_str(chunk.as_str().unwrap());
        }
    }
    assert_eq!(received, text);
    assert!(!cancel.is_cancelled());
}

#[tokio::test]
async fn absent_interest_failures_and_hung_transport_do_not_cancel_execution_or_retry() {
    for mode in 1..=3 {
        let peer = Arc::new(Peer {
            mode,
            requests: Mutex::new(vec![]),
        });
        let cancel = CancellationToken::new();
        let mut lane = TextPreview::new(peer.clone(), json!({}), "call", "message", &cancel);
        lane.accept("answer");
        tokio::time::timeout(Duration::from_millis(800), lane.finish())
            .await
            .unwrap();
        assert!(!cancel.is_cancelled());
        assert_eq!(
            peer.requests.lock().await.len(),
            if mode == 1 { 1 } else { 2 }
        );
    }
}

#[tokio::test]
async fn oversized_queue_stops_only_preview() {
    let peer = Arc::new(Peer {
        mode: 0,
        requests: Mutex::new(vec![]),
    });
    let cancel = CancellationToken::new();
    let mut lane = TextPreview::new(peer.clone(), json!({}), "call", "message", &cancel);
    lane.accept(&"x".repeat(65537));
    lane.finish().await;
    assert!(!cancel.is_cancelled());
    assert!(
        !peer
            .requests
            .lock()
            .await
            .iter()
            .any(|v| v["operation"] == "previews/publish")
    );
}
