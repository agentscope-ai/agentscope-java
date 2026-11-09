# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Small in-cluster compatibility probes; never print credentials or response bodies."""
import json
import os
import urllib.error
import urllib.request


def probe(base_url, model, key):
    results = []
    payloads = {
        "chat/completions": {"model": model, "messages": [{"role": "user", "content": "Reply only OK."}],
                             "max_tokens": 32, "enable_thinking": False},
        "responses": {"model": model, "input": "Reply only OK.", "max_output_tokens": 128,
                      "reasoning": {"effort": "none"}, "store": False},
    }
    for path, payload in payloads.items():
        request = urllib.request.Request(base_url.rstrip("/") + "/" + path,
            data=json.dumps(payload).encode(),
            headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                body = json.load(response)
                results.append({"api": path, "http_status": response.status,
                    "model": body.get("model"), "response_status": body.get("status"),
                    "has_output": bool(body.get("choices") or body.get("output")),
                    "usage": body.get("usage")})
        except urllib.error.HTTPError as error:
            # Some providers echo request details; retain only a machine-readable code.
            try:
                code = json.load(error).get("error", {}).get("code")
            except (ValueError, AttributeError):
                code = None
            results.append({"api": path, "http_status": error.code, "error_code": code})
    return results


if __name__ == "__main__":
    result = probe(os.environ.get("MODEL_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                   os.environ.get("MODEL_ID", "qwen3.8-max"), os.environ["DASHSCOPE_API_KEY"])
    print(json.dumps(result, indent=2), flush=True)
    raise SystemExit(0 if all(item.get("has_output") for item in result) else 1)
