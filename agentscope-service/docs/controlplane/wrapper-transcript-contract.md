# Third-party wrapper transcript contract (BYO)

> Status: C2/D1 — control plane can optionally read this layout; object-store wiring is pluggable.
> Scope: Operate BYO agents that adopt the harness `TranscriptStore` segment layout.

## 1. Key layout

Immutable JSONL segments (never in-place append on a shared object):

```
{tenant}/{agent}/{session}/events/{seqStart}-{seqEnd}-{writerId}.jsonl
```

| Segment | Meaning |
|---------|---------|
| `tenant` | Tenant / namespace id (STS prefix scope) |
| `agent` | Agent id (Operate agent name) |
| `session` | Framework session id |
| `seqStart`–`seqEnd` | Inclusive sequence range covered by the segment |
| `writerId` | Replica / writer id to avoid key collisions under concurrent writers |

Control plane env for shared NAS: `AISTIO_TRANSCRIPT_FS_ROOT=<mount>` maps to the filesystem root that contains `{tenant}/…`.

## 2. JSONL line schema

One UTF-8 JSON object per line. Discriminator field: `type`.

### `message`

```json
{"type":"message","id":"…","parentId":"…","timestamp":"2026-08-01T12:00:00Z","role":"user","content":"…"}
```

Optional: `blockTypes` for placeholders when the original message had no text/tool blocks.

### `tool_use`

```json
{"type":"tool_use","id":"…","parentId":"…","timestamp":"…","toolCallId":"call-1","name":"bash","input":{…},"truncated":false,"originalSize":123}
```

### `tool_result`

```json
{"type":"tool_result","id":"…","parentId":"…","timestamp":"…","toolCallId":"call-1","name":"bash","output":"…","truncated":false,"originalSize":456}
```

Pairing is by `toolCallId` (one call ↔ one result). Multiple tool calls in one assistant turn are separate `tool_use` lines.

Content may be truncated; when so, set `truncated: true` and `originalSize` so consumers distinguish “omitted” from “empty”.

## 3. STS / prefix auth

Grant object-store credentials scoped to the transcript prefix only:

```
{tenant}/{agent}/{session}/
```

Do not issue bucket-wide credentials to wrappers. Prefix-level STS (or equivalent IAM path policies) keeps multi-tenant isolation when Operate and the data plane share the same bucket/NAS.

## 4. Control-plane read path

1. Operate `GET /api/v1/sessions/{id}/messages` tries a CP transcript reader first (`TranscriptMessages` hook / `AISTIO_TRANSCRIPT_FS_ROOT`).
2. On miss, falls back to live DP `FetchMessages`, gated on the `message-query` capability.
3. Transcript hit does **not** require `message-query`.

Aggregates (`entry_count`, tokens) are maintained in `session_transcript_index` from Level-1 DP snapshot fields at write time — not by rescanning transcript objects on every poll.
