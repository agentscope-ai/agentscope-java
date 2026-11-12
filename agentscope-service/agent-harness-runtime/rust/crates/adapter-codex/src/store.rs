//! Incarnation-local staging, never the platform's authoritative persistence layer.
//! Unlike the upstream debug store, imports do not depend on process-local create metadata.
use codex_protocol::ThreadId;
use codex_protocol::protocol::ThreadHistoryMode;
use codex_rollout::{RolloutItem, into_persisted_rollout_items};
use codex_thread_store::{
    AppendThreadItemsParams, ArchiveThreadParams, CreateThreadParams, DeleteThreadParams,
    InMemoryThreadStore, ListThreadsParams, LoadThreadHistoryParams, PersistContext,
    ReadThreadByRolloutPathParams, ReadThreadParams, ResumeThreadParams, StoredThread,
    StoredThreadHistory, ThreadPage, ThreadStore, ThreadStoreError, ThreadStoreFuture,
    UpdateThreadMetadataParams,
};
use std::any::Any;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::Mutex;

struct Entry {
    history: Vec<RolloutItem>,
    open: bool,
}

#[derive(Default)]
pub(crate) struct ScratchStore {
    entries: Mutex<HashMap<ThreadId, Entry>>,
}

fn unsupported<T>(operation: &'static str) -> ThreadStoreFuture<'static, T> {
    Box::pin(async move { Err(ThreadStoreError::Unsupported { operation }) })
}

impl ThreadStore for ScratchStore {
    fn as_any(&self) -> &dyn Any {
        self
    }
    fn create_thread(&self, params: CreateThreadParams) -> ThreadStoreFuture<'_, ()> {
        Box::pin(async move {
            let id = params.thread_id;
            // Reuse upstream's canonical SessionMeta construction, including dynamic tools.
            let seed = InMemoryThreadStore::default();
            seed.create_thread(params).await?;
            let history = seed
                .load_history(LoadThreadHistoryParams {
                    thread_id: id,
                    include_archived: false,
                })
                .await?
                .items;
            let mut entries = self.entries.lock().await;
            if entries.contains_key(&id) {
                return Err(ThreadStoreError::Internal {
                    message: "duplicate scratch thread".into(),
                });
            }
            entries.insert(
                id,
                Entry {
                    history,
                    open: true,
                },
            );
            Ok(())
        })
    }
    fn resume_thread(
        &self,
        params: ResumeThreadParams,
    ) -> ThreadStoreFuture<'_, Arc<Vec<RolloutItem>>> {
        Box::pin(async move {
            let mut entries = self.entries.lock().await;
            if entries.get(&params.thread_id).is_some_and(|e| e.open) {
                return Err(ThreadStoreError::Internal {
                    message: "scratch writer already open".into(),
                });
            }
            let history = params.history.ok_or_else(|| ThreadStoreError::Internal {
                message: "committed history required for import".into(),
            })?;
            entries.insert(
                params.thread_id,
                Entry {
                    history: history.as_ref().clone(),
                    open: true,
                },
            );
            Ok(history)
        })
    }
    fn append_items(&self, params: AppendThreadItemsParams) -> ThreadStoreFuture<'_, ()> {
        Box::pin(async move {
            let mut entries = self.entries.lock().await;
            let entry =
                entries
                    .get_mut(&params.thread_id)
                    .ok_or(ThreadStoreError::ThreadNotFound {
                        thread_id: params.thread_id,
                    })?;
            if !entry.open {
                return Err(ThreadStoreError::Internal {
                    message: "scratch writer closed".into(),
                });
            }
            entry.history.extend(into_persisted_rollout_items(
                params.items,
                ThreadHistoryMode::Legacy,
            ));
            Ok(())
        })
    }
    fn persist_thread(&self, id: ThreadId, _: PersistContext) -> ThreadStoreFuture<'_, ()> {
        self.flush_thread(id)
    }
    fn flush_thread(&self, id: ThreadId) -> ThreadStoreFuture<'_, ()> {
        Box::pin(async move {
            if !self.entries.lock().await.contains_key(&id) {
                return Err(ThreadStoreError::ThreadNotFound { thread_id: id });
            }
            Ok(())
        })
    }
    fn shutdown_thread(&self, id: ThreadId) -> ThreadStoreFuture<'_, ()> {
        Box::pin(async move {
            if let Some(entry) = self.entries.lock().await.get_mut(&id) {
                entry.open = false;
            }
            Ok(())
        })
    }
    fn discard_thread(&self, id: ThreadId) -> ThreadStoreFuture<'_, ()> {
        self.shutdown_thread(id)
    }
    fn load_history(
        &self,
        params: LoadThreadHistoryParams,
    ) -> ThreadStoreFuture<'_, StoredThreadHistory> {
        Box::pin(async move {
            let entries = self.entries.lock().await;
            let entry = entries
                .get(&params.thread_id)
                .ok_or(ThreadStoreError::ThreadNotFound {
                    thread_id: params.thread_id,
                })?;
            Ok(StoredThreadHistory {
                thread_id: params.thread_id,
                items: entry.history.clone(),
                revision: None,
            })
        })
    }
    fn update_thread_metadata(
        &self,
        _: UpdateThreadMetadataParams,
    ) -> ThreadStoreFuture<'_, Option<StoredThread>> {
        // Browser projections are platform-owned. Core's recoverable settings are rollout items.
        Box::pin(async { Ok(None) })
    }
    fn read_thread(&self, _: ReadThreadParams) -> ThreadStoreFuture<'_, StoredThread> {
        unsupported("read_thread")
    }
    fn read_thread_by_rollout_path(
        &self,
        _: ReadThreadByRolloutPathParams,
    ) -> ThreadStoreFuture<'_, StoredThread> {
        unsupported("read_thread_by_rollout_path")
    }
    fn list_threads(&self, _: ListThreadsParams) -> ThreadStoreFuture<'_, ThreadPage> {
        unsupported("list_threads")
    }
    fn archive_thread(&self, _: ArchiveThreadParams) -> ThreadStoreFuture<'_, ()> {
        unsupported("archive_thread")
    }
    fn unarchive_thread(&self, _: ArchiveThreadParams) -> ThreadStoreFuture<'_, StoredThread> {
        unsupported("unarchive_thread")
    }
    fn delete_thread(&self, _: DeleteThreadParams) -> ThreadStoreFuture<'_, ()> {
        unsupported("delete_thread")
    }
}
