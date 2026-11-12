#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Provision disposable RocketMQ infrastructure, never restart the source deployment."""

import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path
import subprocess


LABEL = "managed-agents-suspend-isolation"


def kubectl(args, *command, data=None):
    return subprocess.run(
        ["kubectl", "--kubeconfig", str(args.kubeconfig), "--request-timeout=30s", *command],
        input=data, text=True, capture_output=True, check=True,
    ).stdout


def object_from(args, resource):
    return json.loads(kubectl(args, "-n", args.source_namespace, "get", resource, "-o", "json"))


def provision(args):
    if not args.namespace.startswith("managed-agents-suspend-") or args.namespace == args.source_namespace:
        raise ValueError("A dedicated managed-agents-suspend-* namespace is required")
    args.output.mkdir(parents=True, exist_ok=False)
    source = [object_from(args, x) for x in ["statefulset/rocketmq-broker",
              "deployment/rocketmq-namesrv", "deployment/rocketmq-proxy", "statefulset/redis"]]
    baseline = []
    for x in source:
        baseline.append({"kind": x["kind"], "name": x["metadata"]["name"],
                         "generation": x["metadata"]["generation"],
                         "replicas": x["spec"]["replicas"],
                         "containers": [{"name": c["name"], "image": c["image"]}
                                        for c in x["spec"]["template"]["spec"]["containers"]]})
    (args.output / "source-baseline.json").write_text(json.dumps(baseline, indent=2) + "\n")
    ns = {"apiVersion": "v1", "kind": "Namespace", "metadata": {
        "name": args.namespace, "labels": {"purpose": LABEL}}}
    kubectl(args, "create", "-f", "-", data=json.dumps(ns))
    # Copy registry credentials in memory; never write or print their contents.
    secret = object_from(args, "secret/acr-pull")
    secret["metadata"] = {"name": "acr-pull", "namespace": args.namespace}
    for key in ["status", "immutable"]:
        secret.pop(key, None)
    kubectl(args, "apply", "-f", "-", data=json.dumps(secret))
    storage = json.loads(kubectl(args, "get", "storageclass/managed-agents-test-disk", "-o", "json"))
    storage_name = args.namespace + "-disk"
    storage = {"apiVersion": "storage.k8s.io/v1", "kind": "StorageClass",
               "metadata": {"name": storage_name, "labels": {"purpose": LABEL}},
               "provisioner": storage["provisioner"], "parameters": storage["parameters"],
               "reclaimPolicy": "Delete", "volumeBindingMode": "Immediate"}
    resources = [storage, {"apiVersion": "v1", "kind": "PersistentVolumeClaim",
        "metadata": {"name": "mq-data", "namespace": args.namespace},
        "spec": {"accessModes": ["ReadWriteOnce"], "storageClassName": storage_name,
                 "resources": {"requests": {"storage": "20Gi"}}}}]
    config = object_from(args, "configmap/managed-agents-infra-config")["data"]
    broker_conf = config["broker.conf"].replace("ManagedLiteTest", "SuspendIsolation")
    broker_conf = broker_conf.replace("managed-lite-broker-a", "suspend-probe-broker")
    broker_conf = broker_conf.replace("rocketmq-namesrv", "mq-namesrv")
    proxy_conf = json.loads(config["proxy.json"])
    proxy_conf["rocketMQClusterName"] = "SuspendIsolation"
    proxy_conf["namesrvAddr"] = "mq-namesrv:9876"
    resources.append({"apiVersion": "v1", "kind": "ConfigMap", "metadata": {
        "name": "mq-config", "namespace": args.namespace},
        "data": {"broker.conf": broker_conf, "proxy.json": json.dumps(proxy_conf, indent=2)}})
    for original, name in zip(source[:3], ["mq-broker", "mq-namesrv", "mq-proxy"]):
        x = copy.deepcopy(original)
        x["metadata"] = {"name": name, "namespace": args.namespace, "labels": {"purpose": LABEL}}
        x.pop("status", None)
        x["spec"]["replicas"] = 1
        x["spec"]["selector"] = {"matchLabels": {"app": name}}
        x["spec"]["template"]["metadata"] = {"labels": {"app": name, "purpose": LABEL}}
        if x["kind"] == "StatefulSet":
            x["spec"]["serviceName"] = name
        pod = x["spec"]["template"]["spec"]
        for v in pod.get("volumes", []):
            if "configMap" in v:
                v["configMap"]["name"] = "mq-config"
            if "persistentVolumeClaim" in v:
                v["persistentVolumeClaim"]["claimName"] = "mq-data"
        for c in pod.get("initContainers", []) + pod["containers"]:
            c["args"] = [a.replace("rocketmq-namesrv", "mq-namesrv") for a in c.get("args", [])]
            for e in c.get("env", []):
                if "value" in e:
                    e["value"] = e["value"].replace("rocketmq-namesrv", "mq-namesrv").replace(
                        "ManagedLiteTest", "SuspendIsolation")
        resources.append(x)
    redis_image = source[3]["spec"]["template"]["spec"]["containers"][0]["image"]
    resources.append({"apiVersion": "apps/v1", "kind": "Deployment", "metadata": {
        "name": "probe-redis", "namespace": args.namespace}, "spec": {"replicas": 1,
        "selector": {"matchLabels": {"app": "probe-redis"}}, "template": {"metadata": {
        "labels": {"app": "probe-redis", "purpose": LABEL}}, "spec": {
        "automountServiceAccountToken": False, "imagePullSecrets": [{"name": "acr-pull"}],
        "containers": [{"name": "redis", "image": redis_image,
                        "args": ["redis-server", "--save", "", "--appendonly", "no"],
                        "ports": [{"containerPort": 6379}],
                        "resources": {"requests": {"cpu": "250m", "memory": "256Mi"},
                                      "limits": {"memory": "256Mi"}},
                        "readinessProbe": {"tcpSocket": {"port": 6379}, "periodSeconds": 2}}]}}}})
    for name, port in [("mq-namesrv", 9876), ("mq-broker", 21911), ("mq-proxy", 19981),
                       ("probe-redis", 6379)]:
        spec = {"selector": {"app": name}, "ports": [{"port": port, "targetPort": port}]}
        if name == "mq-broker":
            spec["clusterIP"] = "None"
        resources.append({"apiVersion": "v1", "kind": "Service", "metadata": {
            "name": name, "namespace": args.namespace}, "spec": spec})
    document = {"apiVersion": "v1", "kind": "List", "items": resources}
    (args.output / "infrastructure.json").write_text(json.dumps(document, indent=2) + "\n")
    print(kubectl(args, "apply", "-f", "-", data=json.dumps(document)), end="")
    print("ISOLATION_NAMESPACE=" + args.namespace)


def clients(args):
    if not args.namespace.startswith("managed-agents-suspend-"):
        raise ValueError("Refusing a non-isolated namespace")
    namespace = json.loads(kubectl(args, "get", "namespace", args.namespace, "-o", "json"))
    if namespace["metadata"]["labels"].get("purpose") != LABEL:
        raise ValueError("Namespace is not owned by this verification")
    source = object_from(args, "deployment/service-managed-agents-dataplane")
    image = source["spec"]["template"]["spec"]["containers"][0]["image"]
    classes = args.output / "classes"
    data = {p.name: base64.b64encode(p.read_bytes()).decode() for p in classes.glob("*.class")}
    if not data:
        raise ValueError("Compile LiteSuspendProbe.java before creating clients")
    resources = [{"apiVersion": "v1", "kind": "ConfigMap", "metadata": {
        "name": "probe-code", "namespace": args.namespace}, "binaryData": data, "data": {
        "logback.xml": '<configuration><appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">'
        '<target>System.err</target><encoder><pattern>%date %-5level %logger - %msg%n</pattern>'
        '</encoder></appender><root level="WARN"><appender-ref ref="STDERR"/></root></configuration>'}}]
    common = {"PROBE_RUN": args.namespace, "PROBE_ENDPOINT": "mq-proxy:19981",
              "PROBE_REDIS": "probe-redis:6379", "PROBE_TURN_TOPIC": "suspend_turns",
              "PROBE_RESUME_TOPIC": "suspend_resumes", "PROBE_TURN_GROUP": "suspend_turns_g",
              "PROBE_RESUME_GROUP": "suspend_resumes_g"}
    java = ["java", "-XX:ActiveProcessorCount=2", "-Xms32m", "-Xmx192m",
            "-XX:MaxDirectMemorySize=128m", "-Dloader.main=LiteSuspendProbe",
            "-Dloader.path=/probe", "-Dlogback.configurationFile=/probe/logback.xml",
            "-cp", "/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]
    for name, role in [("probe-a", "turn"), ("probe-b", "turn"), ("probe-resume", "resume")]:
        resources.append({"apiVersion": "apps/v1", "kind": "Deployment", "metadata": {
            "name": name, "namespace": args.namespace}, "spec": {"replicas": 1,
            "selector": {"matchLabels": {"app": name}}, "template": {"metadata": {
            "labels": {"app": name, "purpose": LABEL, "probe-role": role},
            "annotations": {"probe-code-sha256": hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()}}, "spec": {
            "automountServiceAccountToken": False,
            "imagePullSecrets": [{"name": "acr-pull"}],
            "volumes": [{"name": "probe-code", "configMap": {"name": "probe-code"}}],
            "containers": [{"name": "probe", "image": image,
                            "command": java, "args": ["consume", role],
                            "env": [{"name": k, "value": v} for k, v in common.items()],
                            "volumeMounts": [{"name": "probe-code", "mountPath": "/probe",
                                              "readOnly": True}],
                            "resources": {"requests": {"cpu": "500m", "memory": "768Mi"},
                                          "limits": {"memory": "768Mi"}},
                            "readinessProbe": {"exec": {"command": ["sh", "-c", "test -f /tmp/probe-ready"]},
                                               "periodSeconds": 2, "failureThreshold": 5}}]}}}})
    document = {"apiVersion": "v1", "kind": "List", "items": resources}
    (args.output / "clients.json").write_text(json.dumps(document, indent=2) + "\n")
    print(kubectl(args, "apply", "-f", "-", data=json.dumps(document)), end="")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kubeconfig", type=Path, required=True)
    parser.add_argument("--namespace", required=True)
    parser.add_argument("--source-namespace", default="managed-agents-test")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--action", choices=["provision", "clients"], default="provision")
    args = parser.parse_args()
    (provision if args.action == "provision" else clients)(args)
