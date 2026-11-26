#!/usr/bin/env python3
"""Run real ACK redelivery, model takeover and native tool recovery on an isolated Docker network."""

import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PROJECT = "durable-" + uuid.uuid4().hex[:10]
MQ = os.getenv("ROCKETMQ_IMAGE", "apache/rocketmq:5.5.0")
JRE = "eclipse-temurin:21-jre"
CP = "classes:pg.jar:BOOT-INF/classes:BOOT-INF/lib/*"
PACKAGE = "io.agentscope.durable.examples.brainhands.echo."
containers = []


def run(*args, check=True):
    result = subprocess.run(
        args,
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    if check and result.returncode:
        raise RuntimeError(f"{args}: {result.stdout[-4000:]}")
    return result


def docker(*args):
    return run("docker", *args).stdout.strip()


def wait(description, condition, seconds=150):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        value = condition()
        if value:
            return value
        time.sleep(0.5)
    raise TimeoutError(description)


def name(service):
    return PROJECT + "-" + service


def start(service, image, options, *command):
    container = name(service)
    docker(
        "run",
        "-d",
        "--name",
        container,
        "--network",
        PROJECT,
        "--network-alias",
        service,
        *options,
        image,
        *command,
    )
    containers.append(container)


def logs(service):
    return docker("logs", name(service))


def pop_memory_errors(service):
    count = docker(
        "exec",
        name(service),
        "sh",
        "-c",
        """awk '/OutOfDirectMemoryError/{n++} END{print n+0}' "$HOME/logs/rocketmqlogs/pop.log" """,
    )
    return int(count)


def sql(query):
    return docker(
        "exec",
        name("pg"),
        "psql",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        "postgres",
        "-Atc",
        query,
    )


def instrument(source, before, after, folder):
    text = source.read_text()
    if text.count(before) != 1:
        raise RuntimeError("Fault injection site changed: " + str(source))
    dest = folder / source.name
    dest.write_text(text.replace(before, after))
    return dest


def copy_driver(folder):
    # The example marks the driver optional. Resolve only this external artifact so the
    # test also works with an empty local Maven repo and no installed AgentScope jars.
    bom = ROOT / "agentscope-dependencies-bom/pom.xml"
    version = ET.parse(bom).findtext("{*}properties/{*}postgresql.version")
    if not version:
        raise RuntimeError("Missing managed PostgreSQL driver version")
    subprocess.run(
        [
            "mvn",
            "-B",
            "-ntp",
            "-f",
            str(bom),
            "dependency:copy",
            "-Dartifact=org.postgresql:postgresql:" + version,
            "-DoutputDirectory=" + str(folder),
        ],
        cwd=ROOT,
        check=True,
    )
    (folder / ("postgresql-" + version + ".jar")).rename(folder / "pg.jar")


def compile_probes(folder):
    args = [
        "mvn",
        "-B",
        "-ntp",
        "-DskipTests",
        "-Djacoco.skip=true",
        "-Dmaven.javadoc.skip=true",
    ]
    subprocess.run(
        [*args, "-pl", ":agentscope-durable-example-echo-tool", "-am", "package"],
        check=True,
        cwd=ROOT,
    )
    copy_driver(folder)
    example = ROOT / "agentscope-examples/durable/brain-hands/echo-tool"
    with zipfile.ZipFile(next((example / "target").glob("*-brain.jar"))) as jar:
        for path in jar.namelist():
            if path.startswith("BOOT-INF/"):
                jar.extract(path, folder)
    loop = next((ROOT / "agentscope-extensions").rglob("RocketMqLoop.java"))
    before = "receiver.accept(payload);"
    loop = instrument(
        loop,
        before,
        before
        + "\n                                    AckGate.observe(envelope,message,false);",
        folder,
    )
    before = "consumer.ack(message);\n                                } catch"
    instrument(
        loop,
        before,
        "consumer.ack(message);\n                                    AckGate.observe(envelope,message,true);\n                                } catch",
        folder,
    )
    model = instrument(
        next(example.rglob("EchoBrainExample.java")),
        ".text(FINAL_ANSWER)",
        ".text(Probe.answer(turnId, options))",
        folder,
    )
    jdbc_store = next((ROOT / "agentscope-extensions").rglob("JdbcRecordStore.java"))
    site = "if (!absent) s.setString(3, value.toString());\n                }\n                s.executeUpdate();"
    jdbc_store = instrument(
        jdbc_store,
        site,
        site + "\n                CheckpointGate.written(part,value);",
        folder,
    )
    site = "c.commit();\n                return result;"
    instrument(
        jdbc_store,
        site,
        "c.commit();\n                CheckpointGate.committed();\n                return result;",
        folder,
    )
    (folder / "classes").mkdir()
    subprocess.run(
        [
            "javac",
            "--release",
            "17",
            "-cp",
            CP,
            "-d",
            "classes",
            *HERE.glob("*.java"),
            loop,
            model,
            jdbc_store,
        ],
        cwd=folder,
        check=True,
    )


def exercise(folder):
    mount = ["-v", f"{folder}:/lab:ro", "-w", "/lab"]

    def java(service, role, extra_options=()):
        start(
            service,
            JRE,
            [*mount, *extra_options],
            "java",
            "-Xmx256m",
            "-cp",
            CP,
            PACKAGE + "Probe",
            role,
        )
        if role != "model":

            def ready():
                if "READY" in logs(service):
                    return True
                if (
                    docker("inspect", "-f", "{{.State.Running}}", name(service))
                    != "true"
                ):
                    raise RuntimeError(service + " exited before readiness")
                return False

            wait(service + " ready", ready)

    def client(cls, *args, code=0):
        result = run(
            "docker",
            "exec",
            name("client"),
            "java",
            "-Xmx256m",
            "-cp",
            CP,
            PACKAGE + cls,
            *args,
            check=False,
        )
        if result.returncode != code:
            raise RuntimeError(result.stdout[-4000:])
        return result.stdout

    def submit(label):
        return re.search(r"TURN (turn_[\w-]+)", client("Probe", "submit", label))[1]

    def counts(turn):
        condition = f"payload::jsonb->>'turnId'='{turn}'"
        events = f"from durable_parts where part_key like 'event:%' and {condition}"
        return sql(
            "select count(*) filter(where payload::jsonb->>'type'='tool.result.applied'),"
            "count(*) filter(where payload::jsonb#>>'{data,text}'='Hands result applied' and payload::jsonb->>'type'='message.completed'),"
            "count(*) filter(where payload::jsonb->>'type'='turn.succeeded') " + events
        )

    def done(turn):
        wait("applied/final/terminal once " + turn, lambda: counts(turn) == "1|1|1")
        key = sql(
            "select payload::jsonb#>>'{value,request,executionId}' from durable_parts "
            "where part_key like 'turn-data:%:tool:%' and payload::jsonb#>>'{value,request,turnId}'='"
            + turn
            + "'"
        )
        actual = sql(
            f"select count(*),(select count(*) from effects where id='{key}') from starts where id='{key}'"
        )
        if actual != "1|1":
            raise AssertionError((turn, actual))
        print(turn, "starts/effects/applied/answers/success=1/1/1/1/1", flush=True)

    def gate(service, action, file):
        docker("exec", name(service), action, "/tmp/" + file)

    def redeliver(kind, role):
        old, new = role + "-a", role + "-b"
        gate(old, "touch", "hold-" + kind)
        turn = submit(kind)
        pattern = rf"{kind} (\S+) (\d+) {turn}"
        first = wait(
            "pre-ACK " + kind, lambda: re.search("BEFORE " + pattern, logs(old))
        )
        if kind == "result":
            received = sql(
                "select payload::jsonb#>>'{result,state}',payload::jsonb->>'receivedSequence',"
                "payload::jsonb->>'deliveredSequence' from durable_records where record_key like 'queued:%' "
                "and payload::jsonb#>>'{request,turnId}'='" + turn + "'"
            ).split("|")
            assert (
                len(received) == 3
                and received[0] == "SUCCEEDED"
                and received[1] == received[2]
            ), received
        docker("network", "disconnect", PROJECT, name(old))
        java(new, role)

        def retried():
            return next(
                (
                    m
                    for m in re.finditer("ACK " + pattern, logs(new))
                    if m[1] == first[1] and int(m[2]) > int(first[2])
                ),
                None,
            )

        second = wait("same broker message redelivered", retried, 420)
        docker("network", "connect", PROJECT, name(old))
        gate(old, "rm", "hold-" + kind)
        done(turn)
        assert logs("model").count("RECEIVED " + turn) == 1
        print(kind, "broker attempts", first[2], second[2], flush=True)
        return turn

    start("pg", "postgres:17-alpine", ["-e", "POSTGRES_HOST_AUTH_METHOD=trust"])
    start("ns", MQ, ["-e", "JAVA_OPT_EXT=-Xms128m -Xmx128m"], "sh", "mqnamesrv")
    start(
        "broker",
        MQ,
        ["-e", "JAVA_OPT_EXT=-Xms256m -Xmx384m -XX:MaxDirectMemorySize=1g"],
        "sh",
        "-c",
        "printf 'brokerName=regression\nbrokerIP1=broker\nautoCreateTopicEnable=true\n' >/tmp/b.conf && exec sh mqbroker -n ns:9876 -c /tmp/b.conf --enable-proxy",
    )
    start("client", JRE, mount, "sleep", "infinity")
    wait(
        "PostgreSQL",
        lambda: run(
            "docker", "exec", name("pg"), "pg_isready", "-U", "postgres", check=False
        ).returncode
        == 0,
    )
    client("Probe", "init")
    sql(
        "create table starts(id text not null); create table effects(id text primary key)"
    )

    sql(
        "create table native_starts(scenario text, turn_id text, call_id text, receipt text)"
    )
    for scenario in (
        "safe",
        "never",
        "unknown",
        "safe-direct",
        "never-direct",
        "manual-direct",
        "cancel-direct",
    ):
        service = "native-" + scenario
        start(
            service,
            JRE,
            mount,
            "java",
            "-Xmx256m",
            "-cp",
            CP,
            PACKAGE + "NativeReplayProbe",
            scenario,
            "first",
        )
        count = "select count(*) from native_starts where scenario='" + scenario + "'"
        wait("native dispatch " + scenario, lambda: sql(count) == "1")
        docker("kill", name(service))
        operator = scenario in ("manual-direct", "cancel-direct")
        if operator:
            assert "REPAIRED" in client("NativeReplayProbe", scenario, "operator")
        report = client("NativeReplayProbe", scenario, "recover")
        terminal = "PARKED" if scenario == "unknown" else "SUCCEEDED"
        if scenario == "cancel-direct":
            terminal = "CANCELLED"
        match = re.search(r"^" + terminal + r" (turn_[\w-]+)$", report, re.MULTILINE)
        if not match:
            raise AssertionError(report)
        assert sql(count) == ("2" if scenario.startswith("safe") else "1"), report
        assert report.count("MODEL_CHECK") == (
            0 if scenario.startswith("safe") or operator else 1
        ), report
        if scenario.endswith("-direct"):
            assert logs(service).count("REASONING_REQUEST") == 1
            assert report.count("REASONING_REQUEST") == 0, report
        expected = "receipt-7" if scenario.endswith("-direct") else "verified answer"
        answers = sql(
            "select count(*) from durable_parts where part_key like 'event:%' and "
            "payload::jsonb->>'type'='message.completed' and payload::jsonb#>>'{data,text}'='"
            + expected
            + "' and "
            "payload::jsonb->>'turnId'='" + match[1] + "'"
        )
        assert answers == ("0" if scenario in ("unknown", "cancel-direct") else "1")
        print(
            "native",
            scenario,
            terminal,
            "starts",
            sql(count),
            "answers",
            answers,
            flush=True,
        )

    def topics():
        result = run(
            "docker",
            "exec",
            name("broker"),
            "sh",
            "mqadmin",
            "clusterList",
            "-n",
            "ns:9876",
            check=False,
        )
        return result.returncode == 0 and "regression" in result.stdout

    wait("broker registration", topics)
    for suffix in ["commands", "updates", "controls"]:
        docker(
            "exec",
            name("broker"),
            "sh",
            "mqadmin",
            "updateTopic",
            "-n",
            "ns:9876",
            "-c",
            "DefaultCluster",
            "-t",
            "regression-hands-" + suffix,
            "-r",
            "1",
            "-w",
            "1",
        )
    for suffix in ("brain", "worker", "worker-controls"):
        docker(
            "exec",
            name("broker"),
            "sh",
            "mqadmin",
            "updateSubGroup",
            "-n",
            "ns:9876",
            "-c",
            "DefaultCluster",
            "-g",
            "regression-hands-" + suffix,
        )
    java("model", "model")
    java("brain-a", "brain")
    java("hands-a", "hands")
    redeliver("command", "hands")
    redeliver("result", "brain")
    gate("model", "touch", "block")
    turn = submit("model")
    wait("provider received", lambda: "RECEIVED " + turn in logs("model"))
    assert counts(turn) == "1|0|0"
    owner = next(
        s for s in ("brain-a", "brain-b") if "MODEL_REQUEST " + turn in logs(s)
    )
    docker("kill", name(owner))
    wait(
        "model replacement request",
        lambda: logs("model").count("RECEIVED " + turn) >= 2,
    )
    gate("model", "rm", "block")
    done(turn)
    assert logs("model").count("RECEIVED " + turn) == 2
    assert logs("model").count("INFERENCE " + turn) == 1
    print("model takeover: two requests, one inference", flush=True)
    active = "brain-b" if owner == "brain-a" else "brain-a"
    gate("model", "touch", "block")
    turn = submit("cancel-in-flight")
    wait("cancel model barrier", lambda: "RECEIVED " + turn in logs("model"))
    client("Probe", "cancel", turn)
    gate("model", "rm", "block")

    def cancelled():
        return (
            sql(
                "select payload::jsonb#>>'{value,status}' from durable_parts where part_key like 'turn:%' and payload::jsonb#>>'{value,id}'='"
                + turn
                + "'"
            )
            == "CANCELLED"
        )

    wait("cancelled terminal", cancelled)
    assert counts(turn) == "1|0|0"
    print("cancelled in-flight model: applied=1 answers=0 success=0", flush=True)

    checkpoint_session_turn = turn

    def pointer():
        row = sql(
            "select payload::jsonb#>>'{inline,recoveryCursor}',payload::jsonb#>>'{inline,position}' from durable_parts where part_key like '%:checkpoint' and payload::jsonb#>>'{inline,recoveryCursor}' is not null"
            " and record_key in (select record_key from durable_parts where part_key like 'turn:%'"
            " and payload::jsonb#>>'{value,id}'='" + checkpoint_session_turn + "')"
        )
        cursor, position = map(int, row.split("|"))
        return cursor, position

    for phase in ("snapshot", "cursor", "ack", "failed-ack"):
        docker("kill", name(active))
        base, position = pointer()
        if phase == "failed-ack":
            gate("model", "touch", "fail")
        turn = submit("checkpoint-" + phase)
        barrier = "ack" if phase == "failed-ack" else phase
        faulted = "checkpoint-" + phase
        java(
            faulted,
            "brain",
            [
                "-e",
                "CHECKPOINT_GATE=" + barrier,
                "-e",
                "BASE_CURSOR=" + str(base),
                "-e",
                "BASE_POSITION=" + str(position),
            ],
        )
        wait(phase, lambda: "CHECKPOINT_GATE " + barrier in logs(faulted))
        visible = pointer()[0]
        assert (visible > base) == (barrier == "ack"), (phase, base, visible)
        docker("kill", name(faulted))
        active = "replacement-" + phase
        java(active, "brain")
        if phase == "failed-ack":
            gate("model", "rm", "fail")
            failed = sql(
                "select count(*) from durable_parts where part_key like 'event:%' and payload::jsonb->>'turnId'='"
                + turn
                + "' and payload::jsonb->>'type'='turn.failed'"
            )
            assert failed == "1" and counts(turn) == "1|0|0"
            done(submit("after-failure"))
        else:
            done(turn)
        wait("settled checkpoint", lambda: pointer()[0] > base)
        print("checkpoint", phase, "recovered", flush=True)

    for worker in ("hands-a", "hands-b"):
        gate(worker, "touch", "hold-effect")
    turn = submit("effect-before-result")
    old = wait(
        "committed effect",
        lambda: next(
            (
                worker
                for worker in ("hands-a", "hands-b")
                if "EFFECT " + turn in logs(worker)
            ),
            None,
        ),
    )
    other = "hands-b" if old == "hands-a" else "hands-a"
    docker("network", "disconnect", PROJECT, name(old))
    gate(other, "rm", "hold-effect")
    done(turn)
    docker("network", "connect", PROJECT, name(old))
    gate(old, "rm", "hold-effect")
    for worker in (old, other):
        docker("stop", "-t", "2", name(worker))
    done(turn)

    java("opaque", "hands", ["-e", "OPAQUE=true"])
    gate("opaque", "touch", "hold-effect")
    turn = submit("opaque-effect")
    wait("opaque provider commit", lambda: "EFFECT " + turn in logs("opaque"))
    docker("kill", name("opaque"))
    java("opaque-replacement", "hands", ["-e", "OPAQUE=true"])
    row = (
        " from durable_records where record_key like 'execution-pool:%' and payload::jsonb#>>'{request,turnId}'='"
        + turn
        + "'"
    )
    wait(
        "UNKNOWN",
        lambda: sql("select payload::jsonb#>>'{result,state}'" + row) == "UNKNOWN",
    )
    assert counts(turn) == "0|0|0"
    client("Probe", "verify-effect", sql("select payload::jsonb->'request'" + row))
    done(turn)
    print(
        "opaque tool: UNKNOWN verified against committed effect; repeated repair kept one answer",
        flush=True,
    )


if __name__ == "__main__":
    if not __debug__:
        sys.exit("Run without -O so regression assertions are enabled")
    with tempfile.TemporaryDirectory(prefix="durable-regression-") as scratch:
        folder = Path(scratch).resolve()
        compile_probes(folder)
        docker("network", "create", PROJECT)
        try:
            exercise(folder)
            if pop_memory_errors("broker"):
                raise RuntimeError("Broker POP encountered direct-memory exhaustion")
            print("broker POP direct-memory errors=0", flush=True)
        except BaseException:
            for container in containers:
                print(
                    container,
                    run(
                        "docker", "logs", "--tail", "15", container, check=False
                    ).stdout,
                    file=sys.stderr,
                )
            raise
        finally:
            if containers:
                run("docker", "rm", "-f", "-v", *containers)
            docker("network", "rm", PROJECT)
