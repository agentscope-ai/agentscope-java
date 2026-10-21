#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Raw-SDK Suspend gates against disposable in-cluster Broker and consumer replicas."""

import argparse
import json
from pathlib import Path
import socket
import subprocess
import time
import traceback


class Verification:
    def __init__(self, args):
        self.args = args
        self.output = args.output
        self.prefix = "suspend:" + args.namespace + ":"
        self.base = ["kubectl", "--kubeconfig", str(args.kubeconfig), "--request-timeout=30s"]
        self.kube = self.base + ["-n", args.namespace]
        self.command_count = 0
        self.result = {"namespace": args.namespace, "passed": False, "cases": [],
                       "started_at": time.time(), "sdk": "5.2.2", "retry_max_times": 3,
                       "service_main_flow_changed": False}
        self.port_forward = None

    def save(self):
        (self.output / "result.json").write_text(json.dumps(self.result, indent=2) + "\n")

    def command(self, *argv, timeout=70, check=True):
        self.command_count += 1
        completed = subprocess.run(list(argv), text=True, capture_output=True, timeout=timeout)
        (self.output / f"command-{self.command_count:03d}.log").write_text(
            "COMMAND " + " ".join(argv[2:] if argv[:2] == self.base[:2] else argv)
            + "\n" + completed.stdout + completed.stderr)
        if check and completed.returncode:
            raise RuntimeError(f"Command failed ({completed.returncode}): {argv[-8:]}: "
                               + completed.stderr[-1500:] + completed.stdout[-1000:])
        return completed.stdout

    def get(self, resource, *extra):
        return json.loads(self.command(*self.kube, "get", resource, *extra, "-o", "json"))

    def wait(self, description, predicate, timeout=180):
        print("WAIT " + description, flush=True)
        end = time.monotonic() + timeout
        next_progress = time.monotonic() + 20
        while time.monotonic() < end:
            value = predicate()
            if value:
                print("READY " + description, flush=True)
                return value
            if time.monotonic() >= next_progress:
                print("STILL_WAITING " + description, flush=True)
                next_progress += 20
            time.sleep(2)
        raise TimeoutError(description)

    def ready(self, selector):
        pods = self.get("pods", "-l", selector)["items"]
        return pods and all(p.get("status", {}).get("phase") == "Running"
                            and not p["metadata"].get("deletionTimestamp")
                            and any(c["type"] == "Ready" and c["status"] == "True"
                                    for c in p.get("status", {}).get("conditions", [])) for p in pods)

    def admin(self, *argv):
        return self.command(*self.kube, "exec", "mq-broker-0", "-c", "mq-broker", "--",
                            "sh", "mqadmin", *argv)

    def bootstrap(self):
        if not self.args.namespace.startswith("agentscope-suspend-"):
            raise ValueError("Refusing to test outside an isolated namespace")
        ns = json.loads(self.command(*self.base, "get", "namespace", self.args.namespace, "-o", "json"))
        assert ns["metadata"]["labels"].get("purpose") == "agentscope-suspend-isolation"
        for app in ["mq-namesrv", "mq-broker", "mq-proxy", "probe-redis"]:
            self.wait(app + " ready", lambda app=app: self.ready("app=" + app), 420)
        for topic in ["suspend_turns", "suspend_resumes"]:
            self.admin("updateTopic", "-n", "mq-namesrv:9876", "-c", "SuspendIsolation",
                       "-t", topic, "-r", "1", "-w", "1", "-a",
                       "+message.type=LITE,+lite.topic.expiration=1440")
            self.admin("updateSubGroup", "-n", "mq-namesrv:9876", "-c", "SuspendIsolation",
                       "-g", topic + "_g", "-o", "true", "-m", "true", "-r", "3",
                       "--attributes", "+lite.bind.topic=" + topic
                       + ",+lite.sub.model=Shared,+lite.sub.wildcard=true")
        self.result["broker_config"] = self.admin("getBrokerConfig", "-n", "mq-namesrv:9876",
                                                  "-b", "127.0.0.1:21911")
        self.wait("three raw-SDK clients ready", lambda: self.ready("probe-role"), 420)
        log = (self.output / "redis-port-forward.log").open("w")
        self.port_forward = subprocess.Popen(self.kube + ["port-forward", "svc/probe-redis",
                                                    f"{self.args.redis_port}:6379"], stdout=log, stderr=log)
        self.wait("isolated Redis observation channel", self.redis_available, 30)
        metadata = {}
        for pod in self.get("pods", "-l", "probe-role")["items"]:
            name = pod["metadata"]["name"]
            metadata[name] = self.hash(self.prefix + "meta:" + name)
            # retryMaxTimes excludes the original delivery; the SDK advertises four attempts.
            assert int(metadata[name]["max_attempts"]) == self.result["retry_max_times"] + 1, metadata[name]
            assert "rocketmq-client-java-5.2.2.jar" in metadata[name]["sdk_location"], metadata[name]
        self.result["consumer_metadata"] = metadata
        self.result["initial_pods"] = self.snapshot()
        self.save()

    def redis_available(self):
        try:
            return self.redis("PING") == "PONG"
        except (OSError, EOFError):
            return False

    def redis(self, *parts):
        with socket.create_connection(("127.0.0.1", self.args.redis_port), timeout=5) as conn:
            data = [f"*{len(parts)}\r\n".encode()]
            for part in parts:
                b = str(part).encode()
                data += [f"${len(b)}\r\n".encode(), b, b"\r\n"]
            conn.sendall(b"".join(data))
            stream = conn.makefile("rb")

            def read():
                kind = stream.read(1)
                if not kind:
                    raise EOFError("Redis observation connection closed")
                value = stream.readline().removesuffix(b"\r\n")
                if kind == b"-":
                    raise RuntimeError(value.decode())
                if kind == b"+":
                    return value.decode()
                if kind == b":":
                    return int(value)
                length = int(value)
                if length == -1:
                    return None
                if kind == b"$":
                    value = stream.read(length)
                    assert stream.read(2) == b"\r\n"
                    return value.decode()
                if kind == b"*":
                    return [read() for _ in range(length)]
                raise ValueError("Unknown RESP reply")
            return read()

    def hash(self, key):
        values = self.redis("HGETALL", key)
        return dict(zip(values[::2], values[1::2]))

    def state(self, case):
        state = self.hash(self.prefix + "case:" + case)
        assert int(state.get("violations", 0)) == 0, (case, "T2/T3 passed T1", state)
        return state

    def count(self, case, field):
        return int(self.state(case).get(field, 0))

    def sender(self):
        pods = self.get("pods", "-l", "app=probe-resume")["items"]
        return next(p["metadata"]["name"] for p in pods if not p["metadata"].get("deletionTimestamp"))

    def send(self, case, *kinds, role="turn"):
        output = self.command(*self.kube, "exec", self.sender(), "-c", "probe", "--", "java",
                             "-XX:ActiveProcessorCount=2", "-Xms16m", "-Xmx96m",
                             "-XX:MaxDirectMemorySize=64m", "-Dloader.main=LiteSuspendProbe",
                             "-Dloader.path=/probe", "-Dlogback.configurationFile=/probe/logback.xml",
                             "-cp", "/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher",
                             "send", role, case, *kinds)
        with (self.output / "send-ledger.log").open("a") as file:
            file.write(output)
        assert output.count("PROBE_SEND\t") == len(kinds), output

    def start_case(self, case, cycles=12):
        print("CASE_START " + case, flush=True)
        self.redis("HSET", self.prefix + "case:" + case, "phase", "WAITING", "suspend_ms", "500")
        self.send(case, "T1")
        # Establish a received T1 before T2; this avoids conflating initial-offset behavior with Suspend.
        self.wait(case + " T1 anchored", lambda: self.count(case, "suspends") >= 3, 90)
        self.send(case, "T2", "T3", "OTHER")
        self.wait(case + " other Session progresses", lambda: self.count(case, "others") >= 1, 60)
        self.wait(case + " repeated Suspend", lambda: self.count(case, "suspends") >= cycles, 150)
        assert self.count(case, "t2_count") == self.count(case, "t3_count") == 0
        return self.state(case)

    def finish_case(self, case, details=None):
        before = self.state(case)
        assert before["phase"] == "WAITING"
        started = time.monotonic()
        self.send(case, "RESUME", role="resume")
        self.wait(case + " Resume independently commits DONE", lambda: self.count(case, "resumes") >= 1, 60)
        self.wait(case + " T1 then T2 then T3", lambda: self.count(case, "t3_count") >= 1, 60)
        state = self.state(case)
        assert int(state["resume_seq"]) < int(state["t1_success_seq"]) < int(state["t2_seq"]) < int(state["t3_seq"]), state
        result = {"name": case, "passed": True, "before_resume": before, "final": state,
                  "resume_to_success_seconds": round(time.monotonic() - started, 3), **(details or {})}
        self.result["cases"].append(result)
        self.save()
        print("CASE_PASSED " + case + " " + json.dumps(state), flush=True)

    def snapshot(self):
        return [{"name": p["metadata"]["name"], "uid": p["metadata"]["uid"],
                 "ip": p.get("status", {}).get("podIP"),
                 "containers": [{k: c.get(k) for k in ["name", "image", "imageID", "restartCount"]}
                                for c in p.get("status", {}).get("containerStatuses", [])]}
                for p in self.get("pods")["items"]]

    def replica_switch(self):
        case = "consumer-switch"
        state = self.start_case(case)
        owner = state["last_t1_pod"]
        pod = self.get("pod/" + owner)
        deployment = pod["metadata"]["labels"]["app"]
        assert deployment in ["probe-a", "probe-b"]
        count = int(state["suspends"])
        print("FAULT scale " + deployment + " to zero; owner=" + owner, flush=True)
        self.command(*self.kube, "scale", "deployment/" + deployment, "--replicas=0")
        self.wait("T1 transferred to surviving consumer", lambda: self.count(case, "suspends") >= count + 12
                  and self.state(case)["last_t1_pod"] != owner, 180)
        self.finish_case(case, {"stopped_deployment": deployment, "old_owner": owner,
                               "surviving_owner": self.state(case)["last_t1_pod"]})
        self.command(*self.kube, "scale", "deployment/" + deployment, "--replicas=1")
        self.wait("both consumers restored", lambda: self.ready("probe-role=turn")
                  and len(self.get("pods", "-l", "probe-role=turn")["items"]) == 2, 420)

    def broker_fault(self, replace_pod=False):
        case = "broker-pod-replacement" if replace_pod else "broker-hard-crash"
        state = self.start_case(case)
        before = self.get("pod/mq-broker-0")
        before_restarts = before["status"]["containerStatuses"][0]["restartCount"]
        count = int(state["suspends"])
        started = time.monotonic()
        if replace_pod:
            print("FAULT replace isolated Broker Pod, keeping its dedicated PVC", flush=True)
            self.command(*self.kube, "delete", "pod/mq-broker-0", "--grace-period=10", "--wait=false")
        else:
            print("FAULT SIGKILL isolated Broker JVM", flush=True)
            script = ('for p in /proc/[0-9]*; do '
                      '[ "$(cat "$p/comm" 2>/dev/null)" = java ] || continue; '
                      'case "$(tr "\\000" " " < "$p/cmdline")" in '
                      '*org.apache.rocketmq.broker.BrokerStartup*) '
                      'echo KILL_BROKER_PID=${p##*/}; kill -9 "${p##*/}"; exit 0;; esac; '
                      'done; echo BROKER_JVM_NOT_FOUND >&2; exit 1')
            self.command(*self.kube, "exec", "mq-broker-0", "-c", "mq-broker", "--", "sh", "-ec", script)

        def recovered():
            current = self.command(*self.kube, "get", "pod/mq-broker-0", "-o", "json", check=False)
            if not current.strip():
                return False
            pod = json.loads(current)
            statuses = pod.get("status", {}).get("containerStatuses", [])
            changed = pod["metadata"]["uid"] != before["metadata"]["uid"] if replace_pod else (
                statuses and statuses[0]["restartCount"] > before_restarts)
            return changed and not pod["metadata"].get("deletionTimestamp") and statuses and all(c.get("ready") for c in statuses)

        self.wait(case + " Broker ready after fault", recovered, 480)
        self.wait(case + " suspended T1 redelivered after recovery",
                  lambda: self.count(case, "suspends") >= count + 12, 180)
        assert self.count(case, "t2_count") == self.count(case, "t3_count") == 0
        after = self.get("pod/mq-broker-0")
        self.finish_case(case, {"broker_uid_before": before["metadata"]["uid"],
                               "broker_uid_after": after["metadata"]["uid"],
                               "broker_ip_before": before["status"].get("podIP"),
                               "broker_ip_after": after["status"].get("podIP"),
                               "recovery_seconds": round(time.monotonic() - started, 3)})

    def failure_control(self):
        case = "failure-control"
        self.send(case, "FAIL")
        self.wait("FAILURE reaches configured maximum attempts", lambda: self.count(case, "failures") >= 4, 90)
        self.send(case, "AFTER_FAIL")
        self.wait("failure control leaves queue after maximum attempts", lambda: self.count(case, "after_fail") == 1, 90)
        state = self.state(case)
        assert int(state["failures"]) == int(state["last_failure_attempt"]) == 4, state
        self.result["cases"].append({"name": case, "passed": True, "final": state})
        self.save()
        print("CASE_PASSED failure-control " + json.dumps(state), flush=True)

    def collect(self):
        try:
            events = self.redis("LRANGE", self.prefix + "events", "0", "-1")
            (self.output / "events.tsv").write_text("seq\ttimestamp_ms\tcase\taction\tpod\tdelivery_attempt\tphase\tviolation\tmessage_id\n"
                                                   + "\n".join(events) + "\n")
        except Exception as error:
            self.result["event_collection_error"] = str(error)
        for pod in self.get("pods")["items"]:
            name = pod["metadata"]["name"]
            for c in pod["spec"]["containers"]:
                for previous in [False, True]:
                    command = self.kube + ["logs", name, "-c", c["name"], "--timestamps=true"]
                    if previous:
                        command += ["--previous"]
                    text = self.command(*command, check=False)
                    if text:
                        (self.output / (name + "-" + c["name"] + ("-previous" if previous else "") + ".log")).write_text(text)
        self.result["final_pods"] = self.snapshot()
        self.save()

    def run(self):
        try:
            self.bootstrap()
            self.start_case("repeated-suspend", 120)
            self.finish_case("repeated-suspend")
            self.replica_switch()
            self.broker_fault()
            self.broker_fault(replace_pod=True)
            self.failure_control()
            self.result["passed"] = True
        except Exception as error:
            self.result["failure"] = repr(error)
            self.result["traceback"] = traceback.format_exc()
            raise
        finally:
            self.result["finished_at"] = time.time()
            if self.port_forward is not None:
                try:
                    self.collect()
                finally:
                    self.port_forward.terminate()
                    self.port_forward.wait(timeout=10)
            self.save()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kubeconfig", type=Path, required=True)
    parser.add_argument("--namespace", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--redis-port", type=int, default=16390)
    Verification(parser.parse_args()).run()
