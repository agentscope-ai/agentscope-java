#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""SDK-only DLQ diagnostics in a namespace provisioned by infra.py."""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import time


class Matrix:
    def __init__(self, args):
        self.args = args
        self.out = args.output
        self.base = ["kubectl", "--kubeconfig", str(args.kubeconfig), "--request-timeout=30s"]
        self.kube = self.base + ["-n", args.namespace]
        self.results = []

    def command(self, *argv, check=True, timeout=90, data=None):
        p = subprocess.run(argv, input=data, text=True, capture_output=True, timeout=timeout)
        if check and p.returncode:
            raise RuntimeError(str(argv[-8:]) + ": " + p.stderr[-2000:] + p.stdout[-1000:])
        return p.stdout + (p.stderr if not check else "")

    def get(self, resource, *extra):
        return json.loads(self.command(*self.kube, "get", resource, *extra, "-o", "json"))

    def wait(self, label, predicate, timeout=420):
        print("WAIT " + label, flush=True)
        end = time.monotonic() + timeout
        progress = 0
        while time.monotonic() < end:
            value = predicate()
            if value:
                print("READY " + label, flush=True)
                return value
            if time.monotonic() >= progress:
                print("PENDING " + label, flush=True)
                progress = time.monotonic() + 25
            time.sleep(2)
        raise TimeoutError(label)

    def ready(self, app):
        pods = self.get("pods", "-l", "app=" + app)["items"]
        return pods and all(not p["metadata"].get("deletionTimestamp") and
                           any(c["type"] == "Ready" and c["status"] == "True"
                               for c in p.get("status", {}).get("conditions", [])) for p in pods)

    def admin(self, case, *argv):
        text = self.command(*self.kube, "exec", "mq-broker-0", "-c", "mq-broker", "--",
                            "sh", "mqadmin", *argv)
        (self.out / (case + "-admin-" + argv[0] + ".log")).write_text(text)
        return text

    def spec(self, case):
        return {"name": case, "mode": "standard" if case in ["fifo", "late-fifo"] else "lite",
                "accelerator": case != "accelerator-off",
                "subscription": "explicit" if case == "explicit" else "wildcard",
                "topic": "diag_" + case.replace("-", "_"),
                "group": "diag_" + case.replace("-", "_") + "_g",
                "app": "dlq-" + case}

    def setup(self, cases):
        assert self.args.namespace.startswith("agentscope-suspend-")
        ns = json.loads(self.command(*self.base, "get", "namespace", self.args.namespace, "-o", "json"))
        assert ns["metadata"]["labels"]["purpose"] == "agentscope-suspend-isolation"
        for app in ["mq-namesrv", "mq-broker", "mq-proxy"]:
            self.wait(app, lambda app=app: self.ready(app))
        source = json.loads(self.command(*self.base, "-n", "agentscope-test", "get",
                                        "deployment/service-agentscope-dataplane", "-o", "json"))
        image = source["spec"]["template"]["spec"]["containers"][0]["image"]
        binary = (self.out / "classes/DeadLetterProbe.class").read_bytes()
        code = {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {
            "name": "dlq-code", "namespace": self.args.namespace}, "binaryData": {
            "DeadLetterProbe.class": base64.b64encode(binary).decode()}}
        self.command(*self.kube, "apply", "-f", "-", data=json.dumps(code))
        for case in cases:
            c = self.spec(case)
            attrs = "+message.type=FIFO" if c["mode"] == "standard" else (
                "+message.type=LITE,+lite.topic.expiration=1440")
            self.admin(case, "updateTopic", "-n", "mq-namesrv:9876", "-c", "SuspendIsolation",
                       "-t", c["topic"], "-r", "1", "-w", "1", "-a", attrs)
            group = ["updateSubGroup", "-n", "mq-namesrv:9876", "-c", "SuspendIsolation",
                     "-g", c["group"], "-o", "true", "-m", "true", "-r", "3"]
            if c["mode"] == "lite":
                group += ["--attributes", "+lite.bind.topic=" + c["topic"]
                          + ",+lite.sub.model=Shared"
                          + (",+lite.sub.wildcard=true" if c["subscription"] == "wildcard" else "")]
            self.admin(case, *group)
            java = ["java", "-XX:ActiveProcessorCount=2", "-Xms32m", "-Xmx192m",
                    "-XX:MaxDirectMemorySize=128m", "-Dloader.main=DeadLetterProbe",
                    "-Dloader.path=/probe", "-cp", "/app.jar",
                    "org.springframework.boot.loader.launch.PropertiesLauncher"]
            args = ["consume", c["mode"], c["topic"], c["group"], case,
                    str(c["accelerator"]).lower(), c["subscription"]]
            manifest = {"apiVersion": "apps/v1", "kind": "Deployment", "metadata": {
                "name": c["app"], "namespace": self.args.namespace}, "spec": {"replicas": 1,
                "selector": {"matchLabels": {"app": c["app"]}}, "template": {"metadata": {
                "labels": {"app": c["app"], "purpose": "agentscope-suspend-isolation"},
                "annotations": {"probe-sha256": hashlib.sha256(binary).hexdigest()}}, "spec": {
                "automountServiceAccountToken": False, "imagePullSecrets": [{"name": "acr-pull"}],
                "volumes": [{"name": "code", "configMap": {"name": "dlq-code"}}],
                "containers": [{"name": "probe", "image": image, "command": java, "args": args,
                "volumeMounts": [{"name": "code", "mountPath": "/probe", "readOnly": True}],
                "resources": {"requests": {"cpu": "500m", "memory": "768Mi"},
                              "limits": {"memory": "768Mi"}},
                "readinessProbe": {"exec": {"command": ["sh", "-c", "test -f /tmp/dlq-ready"]},
                                   "periodSeconds": 2}}]}}}}
            (self.out / (case + "-consumer.json")).write_text(json.dumps(manifest, indent=2) + "\n")
            self.command(*self.kube, "apply", "-f", "-", data=json.dumps(manifest))
        for case in cases:
            self.wait(case + " consumer", lambda case=case: self.ready(self.spec(case)["app"]))

    def logs(self, case):
        return self.command(*self.kube, "logs", "deployment/" + self.spec(case)["app"])

    def events(self, logs):
        rows = []
        for line in logs.splitlines():
            if not line.startswith("DLQ_EVENT\t"):
                continue
            _, timestamp, kind, attempt, topic, lite, offset, receipt, message_id, props = line.split("\t")
            rows.append({"timestamp": int(timestamp), "kind": kind, "attempt": int(attempt),
                         "topic": topic, "lite": lite, "offset": int(offset),
                         "receipt": base64.b64decode(receipt).decode(), "id": message_id,
                         "properties": base64.b64decode(props).decode()})
        return rows

    def send(self, case, *kinds, message_case=None):
        c = self.spec(case)
        text = self.command(*self.kube, "exec", "deployment/" + c["app"], "--",
            "java", "-XX:ActiveProcessorCount=2", "-Xms16m", "-Xmx96m",
            "-XX:MaxDirectMemorySize=64m", "-Dloader.main=DeadLetterProbe", "-Dloader.path=/probe",
            "-cp", "/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher",
            "send", c["mode"], c["topic"], c["group"], message_case or case, *kinds)
        with (self.out / (case + "-send.log")).open("a") as f:
            f.write(text)
        assert text.count("DLQ_SEND\t") == len(kinds), text

    def run_case(self, case):
        print("CASE " + case, flush=True)
        late = case.startswith("late") or case in ["patched", "reverted"]
        if case.startswith("late-skew"):
            self.send(case, "SEED", message_case=case + "-other")
            self.wait(case + " other LiteTopic seed", lambda: any(r["kind"] == "SEED" for r in self.events(self.logs(case))), 90)
        if not late or case in ["late-seeded", "late-skew-seeded"]:
            self.send(case, "SEED")
            self.wait(case + " seed", lambda: any(r["kind"] == "SEED" for r in self.events(self.logs(case))), 90)
        if late:
            self.send(case, "FAIL")
            self.wait(case + " successful DLQ response", lambda: "Forward message to dead letter queue successfully"
                      in self.command(*self.kube, "exec", "deployment/" + self.spec(case)["app"], "--",
                                      "cat", "/data/logs/rocketmq/rocketmq-client.log"), 90)
            self.send(case, "AFTER_FAIL")
        else:
            self.send(case, "FAIL", "AFTER_FAIL")
        start = time.monotonic()
        while time.monotonic() - start < self.args.observe_seconds:
            time.sleep(2)
        logs = self.logs(case)
        rows = self.events(logs)
        failed = [r for r in rows if r["kind"] == "FAIL"]
        result = {**self.spec(case), "observed_seconds": self.args.observe_seconds,
                  "fail_callbacks": len(failed), "failure_attempts": [r["attempt"] for r in failed],
                  "after_received": any(r["kind"] == "AFTER_FAIL" for r in rows),
                  "fail_actual_topics": sorted(set(r["topic"] for r in failed)), "events": rows}
        self.results.append(result)
        (self.out / (case + "-result.json")).write_text(json.dumps(result, indent=2) + "\n")
        (self.out / (case + "-stdout.log")).write_text(logs)
        sdk = self.command(*self.kube, "exec", "deployment/" + self.spec(case)["app"], "--",
                           "cat", "/data/logs/rocketmq/rocketmq-client.log", check=False)
        (self.out / (case + "-sdk.log")).write_text(sdk)
        for name in ["lmqConsumerOffset.json", "consumerOrderInfo.json", "subscriptionGroup.json"]:
            text = self.command(*self.kube, "exec", "mq-broker-0", "-c", "mq-broker", "--",
                                "cat", "/home/rocketmq/store/config/" + name)
            (self.out / (case + "-" + name)).write_text(text)
        print("OBSERVED " + json.dumps({k: v for k, v in result.items() if k != "events"}), flush=True)
        # Stop only this case's consumer after its evidence is captured.
        self.command(*self.kube, "scale", "deployment/" + self.spec(case)["app"], "--replicas=0")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--kubeconfig", type=Path, required=True)
    p.add_argument("--namespace", required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--cases", default="baseline,accelerator-off,explicit,fifo")
    p.add_argument("--observe-seconds", type=int, default=45)
    args = p.parse_args()
    matrix = Matrix(args)
    cases = args.cases.split(",")
    matrix.setup(cases)
    for case in cases:
        matrix.run_case(case)
