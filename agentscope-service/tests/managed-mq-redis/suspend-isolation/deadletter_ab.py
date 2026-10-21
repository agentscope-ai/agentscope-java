#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Apply/remove a single diagnostic class override in an owned disposable Broker."""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import time


def main(args):
    assert args.namespace.startswith("agentscope-suspend-") and args.namespace != "agentscope-test"
    base = ["kubectl", "--kubeconfig", str(args.kubeconfig), "--request-timeout=30s"]
    kube = base + ["-n", args.namespace]

    def command(*argv, data=None):
        return subprocess.check_output(argv, input=data, text=True)

    def get(*argv):
        return json.loads(command(*kube, "get", *argv, "-o", "json"))

    ns = json.loads(command(*base, "get", "namespace", args.namespace, "-o", "json"))
    assert ns["metadata"]["labels"].get("purpose") == "agentscope-suspend-isolation"
    deployments = get("deployments")["items"]
    assert all(d["spec"]["replicas"] == 0 for d in deployments
               if d["metadata"]["name"].startswith("dlq-")), "Finish all probe cases before Broker rollout"
    sts = get("statefulset/mq-broker")
    pod = get("pod/mq-broker-0")
    (args.output / (args.action + "-broker-before.json")).write_text(json.dumps(sts, indent=2) + "\n")
    spec = sts["spec"]["template"]["spec"]
    assert len(spec["containers"]) == 1 and spec["containers"][0]["name"] == "mq-broker"
    name = "diagnostic-dispatch-filter"
    spec["volumes"] = [v for v in spec.get("volumes", []) if v["name"] != name]
    container = spec["containers"][0]
    container["volumeMounts"] = [v for v in container.get("volumeMounts", []) if v["name"] != name]
    digest = None
    if args.action == "install":
        binary = (args.output / "dispatch-patched-classes/org/apache/rocketmq/store/queue/MultiDispatchUtils.class").read_bytes()
        digest = hashlib.sha256(binary).hexdigest()
        cm = {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {
            "name": name, "namespace": args.namespace}, "binaryData": {
            "MultiDispatchUtils.class": base64.b64encode(binary).decode()}}
        command(*kube, "apply", "-f", "-", data=json.dumps(cm))
        spec["volumes"].append({"name": name, "configMap": {"name": name, "items": [{
            "key": "MultiDispatchUtils.class", "path": "apache/rocketmq/store/queue/MultiDispatchUtils.class"}]}})
        # The original runbroker.sh puts its working directory first on the classpath.
        container["volumeMounts"].append({"name": name,
            "mountPath": "/home/rocketmq/rocketmq-5.5.1/bin/org", "readOnly": True})
    patch = {"spec": {"template": {"metadata": {"annotations": {
        "diagnostic-dispatch-filter-sha256": digest}}, "spec": spec}}}
    (args.output / (args.action + "-broker-patch.json")).write_text(json.dumps(patch, indent=2) + "\n")
    print(command(*kube, "patch", "statefulset/mq-broker", "--type=merge", "-p", json.dumps(patch)), flush=True)
    end = time.monotonic() + 480
    progress = 0
    while time.monotonic() < end:
        text = command(*kube, "get", "pod/mq-broker-0", "--ignore-not-found", "-o", "json")
        after = json.loads(text) if text.strip() else {}
        statuses = after.get("status", {}).get("containerStatuses", [])
        if (after.get("metadata", {}).get("uid") not in [None, pod["metadata"]["uid"]]
                and not after["metadata"].get("deletionTimestamp") and statuses
                and all(c.get("ready") for c in statuses)):
            (args.output / (args.action + "-broker-after.json")).write_text(json.dumps(after, indent=2) + "\n")
            print("READY new isolated Broker Pod " + after["metadata"]["uid"], flush=True)
            if args.action == "remove":
                command(*kube, "delete", "configmap", name, "--ignore-not-found")
            return
        if time.monotonic() >= progress:
            print("WAIT isolated Broker rollout: " + args.action, flush=True)
            progress = time.monotonic() + 25
        time.sleep(3)
    raise TimeoutError("Isolated Broker rollout: " + args.action)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--kubeconfig", type=Path, required=True)
    p.add_argument("--namespace", required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--action", choices=["install", "remove"], required=True)
    main(p.parse_args())
