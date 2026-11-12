use managed_brain_runtime::kernel::ModelGate;
use managed_brain_runtime::preview::TextPreview;
use managed_brain_runtime::protocol::framed_hash;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

/// Parse only public text. This never forwards executable items to the native Core.
pub(crate) struct StreamPreviews {
    gate: Arc<dyn ModelGate>,
    model_call: String,
    thread: String,
    line: Vec<u8>,
    data: String,
    messages: HashMap<String, Option<TextPreview>>,
    disabled: bool,
}

impl StreamPreviews {
    pub fn new(gate: Arc<dyn ModelGate>, model_call: String, thread: String) -> Self {
        Self {
            gate,
            model_call,
            thread,
            line: Vec::new(),
            data: String::new(),
            messages: HashMap::new(),
            disabled: false,
        }
    }

    pub fn accept(&mut self, bytes: &[u8]) {
        for byte in bytes {
            if self.disabled {
                return;
            }
            if *byte != b'\n' {
                if self.line.len() + self.data.len() >= 64 * 1024 {
                    self.disable();
                    return;
                }
                self.line.push(*byte);
                continue;
            }
            let line = std::mem::take(&mut self.line);
            let Ok(line) = std::str::from_utf8(&line) else {
                self.disable();
                return;
            };
            let line = line.strip_suffix('\r').unwrap_or(line);
            if line.is_empty() {
                let data = std::mem::take(&mut self.data);
                if data.is_empty() || data.trim() == "[DONE]" {
                    continue;
                }
                let Ok(event) = serde_json::from_str(&data) else {
                    self.disable();
                    return;
                };
                self.event(event);
            } else if let Some(data) = line.strip_prefix("data:") {
                if !self.data.is_empty() {
                    self.data.push('\n');
                }
                self.data.push_str(data.strip_prefix(' ').unwrap_or(data));
            }
        }
    }

    fn event(&mut self, event: Value) {
        if event["type"] == "response.output_item.added" {
            let item = &event["item"];
            if item["id"]
                .as_str()
                .is_some_and(|id| self.messages.contains_key(id))
            {
                self.disable();
                return;
            }
            if item["type"] != "message"
                || item["role"] != "assistant"
                || item["channel"] == "analysis"
            {
                return;
            }
            let Some(id) = item["id"]
                .as_str()
                .filter(|id| !id.is_empty() && id.len() <= 256)
            else {
                return;
            };
            // If a provider starts with prefilled content, subsequent deltas are not a
            // complete prefix. Fall back to the authoritative final message.
            if item["content"].as_array().is_some_and(|parts| {
                parts.iter().any(|part| {
                    part["type"] != "output_text"
                        || part["text"].as_str().is_some_and(|text| !text.is_empty())
                })
            }) {
                return;
            }
            if self.messages.len() >= 128 || self.messages.contains_key(id) {
                self.disable();
                return;
            }
            self.messages.insert(id.to_owned(), None);
        } else if event["type"] == "response.output_text.delta" {
            let (Some(id), Some(text)) = (event["item_id"].as_str(), event["delta"].as_str())
            else {
                return;
            };
            let Some(preview) = self.messages.get_mut(id) else {
                return;
            };
            if text.is_empty() {
                return;
            }
            preview
                .get_or_insert_with(|| {
                    self.gate.text_preview(
                        &self.model_call,
                        &format!("message_{}", framed_hash(&[&self.thread, id])),
                    )
                })
                .accept(text);
        }
        // Reasoning, prompts, tool arguments and arbitrary provider payloads are private.
    }

    fn disable(&mut self) {
        self.disabled = true;
        self.line.clear();
        self.data.clear();
        self.messages.clear();
    }

    pub async fn finish(self) {
        let deadline = tokio::time::Instant::now() + Duration::from_millis(200);
        for preview in self.messages.into_values().flatten() {
            let _ = tokio::time::timeout_at(deadline, preview.finish()).await;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use anyhow::Result;
    use async_trait::async_trait;
    use managed_brain_runtime::client::Transport;
    use managed_brain_runtime::kernel::{Checkpoint, ModelUsage};
    use serde_json::json;
    use tokio::sync::Mutex;
    use tokio_util::sync::CancellationToken;

    #[derive(Default)]
    struct Peer {
        batches: Mutex<Vec<Value>>,
    }
    #[async_trait]
    impl Transport for Peer {
        async fn post(&self, operation: &str, request: &Value) -> Result<Value> {
            if operation == "previews/interest" {
                return Ok(json!({"interested":true}));
            }
            self.batches.lock().await.push(request.clone());
            Ok(json!({"accepted":true}))
        }
    }
    struct Gate(Arc<Peer>);
    #[async_trait]
    impl ModelGate for Gate {
        fn text_preview(&self, call: &str, message: &str) -> TextPreview {
            TextPreview::new(
                self.0.clone(),
                json!({}),
                call,
                message,
                &CancellationToken::new(),
            )
        }
        async fn before_request(&self, _: Value, _: Checkpoint, _: &str) -> Result<String> {
            unreachable!()
        }
        async fn after_response(
            &self,
            _: String,
            _: Value,
            _: Option<ModelUsage>,
            _: Checkpoint,
        ) -> Result<()> {
            unreachable!()
        }
        async fn reject_response(
            &self,
            _: String,
            _: Value,
            _: Option<ModelUsage>,
            _: Checkpoint,
        ) -> Result<()> {
            unreachable!()
        }
    }

    #[tokio::test]
    async fn fragmented_utf8_and_crlf_publish_only_declared_assistant_text() {
        let peer = Arc::new(Peer::default());
        let mut stream =
            StreamPreviews::new(Arc::new(Gate(peer.clone())), "call".into(), "thread".into());
        let events = [
            json!({"type":"response.output_item.added","item":{"id":"thought","type":"reasoning"}}),
            json!({"type":"response.output_text.delta","item_id":"thought","delta":"private"}),
            json!({"type":"response.output_item.added","item":{"id":"analysis","type":"message","role":"assistant","channel":"analysis","content":[]}}),
            json!({"type":"response.output_text.delta","item_id":"analysis","delta":"private"}),
            json!({"type":"response.output_item.added","item":{"id":"answer","type":"message","role":"assistant","content":[]}}),
            json!({"type":"response.function_call_arguments.delta","item_id":"answer","delta":"private"}),
            json!({"type":"response.output_text.delta","item_id":"answer","delta":"你好🙂"}),
        ].iter().map(|event|format!("data: {event}\r\n\r\n")).collect::<String>();
        for byte in events.as_bytes() {
            stream.accept(&[*byte]);
        }
        stream.finish().await;
        let batches = peer.batches.lock().await;
        assert_eq!(batches.len(), 1);
        assert_eq!(batches[0]["chunks"], json!(["你好🙂"]));
        assert_eq!(
            batches[0]["messageId"],
            format!("message_{}", framed_hash(&["thread", "answer"]))
        );
    }

    #[tokio::test]
    async fn malformed_or_unbounded_frames_stop_preview_without_returning_execution_errors() {
        for bytes in [b"data: invalid\n\n".to_vec(), vec![b'x'; 65537]] {
            let peer = Arc::new(Peer::default());
            let mut stream =
                StreamPreviews::new(Arc::new(Gate(peer.clone())), "call".into(), "thread".into());
            stream.accept(&bytes);
            assert!(stream.disabled);
            stream.finish().await;
            assert!(peer.batches.lock().await.is_empty());
        }
    }
}
