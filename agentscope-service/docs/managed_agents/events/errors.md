# Unified API errors

HTTP responses on `/api/**` use:

```jsonc
{
  "type": "error",
  "error": {
    "type": "invalid_request_error",
    "code": "unknown_event_type",
    "message": "Unknown event type: foo.bar",
    "param": "events[].type",
    "session_id": "sess_..."
  }
}
```

| `error.type` | HTTP |
|---|---|
| `invalid_request_error` | 400 |
| `authentication_error` | 401 |
| `permission_error` | 403 |
| `not_found_error` | 404 |
| `conflict_error` | 409 |
| `rate_limit_error` | 429 |
| `api_error` | 500 |

`session.error` event payloads use the same inner `error` object (plus optional `retry_status`: `retrying` | `not_retrying` | `exhausted`).
