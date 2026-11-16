#!/usr/bin/env python3
"""Compare paired SimpleConsumers on separate RocketMQ NameServer, Broker and Proxy JVMs."""

from collections import Counter
from pathlib import Path
import tempfile
import time

import run as lab


def exercise(folder, memory):
    ns, broker, proxy = [memory + "-" + role for role in ("ns", "broker", "proxy")]
    lab.start(ns, lab.MQ, ["-e", "JAVA_OPT_EXT=-Xms128m -Xmx128m"], "sh", "mqnamesrv")
    lab.start(
        broker,
        lab.MQ,
        ["-e", f"JAVA_OPT_EXT=-Xms512m -Xmx1g -XX:MaxDirectMemorySize={memory}"],
        "sh",
        "-c",
        f"printf 'brokerName=paired\nbrokerIP1={broker}\nautoCreateTopicEnable=true\nautoCreateSubscriptionGroup=true\n' >/tmp/b.conf && exec sh mqbroker -n {ns}:9876 -c /tmp/b.conf",
    )

    def admin(*options):
        return lab.run(
            "docker",
            "exec",
            lab.name(broker),
            "sh",
            "mqadmin",
            *options,
            "-n",
            ns + ":9876",
            check=False,
        )

    lab.wait("broker registration", lambda: "paired" in admin("clusterList").stdout)
    for args in (
        (
            "updateTopic",
            "-c",
            "DefaultCluster",
            "-t",
            "paired-commands",
            "-r",
            "8",
            "-w",
            "8",
        ),
        ("updateSubGroup", "-c", "DefaultCluster", "-g", "paired-hands"),
    ):
        lab.wait(args[0], lambda: admin(*args).returncode == 0)
    config = (
        '{"namesrvAddr":"'
        + ns
        + ':9876","localServeAddr":"127.0.0.1","grpcServerPort":8081}'
    )
    lab.start(
        proxy,
        lab.MQ,
        ["-e", "JAVA_OPT_EXT=-Xms512m -Xmx1g -XX:MaxDirectMemorySize=1g"],
        "sh",
        "-c",
        f"echo '{config}' >/tmp/proxy.json && exec sh mqproxy -n {ns}:9876 -pc /tmp/proxy.json -pm cluster",
    )
    lab.wait("proxy startup", lambda: "startup successfully" in lab.logs(proxy))

    clients = [memory + "-a", memory + "-b"]
    options = [
        "--network",
        "container:" + lab.name(proxy),
        "-v",
        f"{folder}:/lab:ro",
        "-w",
        "/lab",
    ]
    java = [
        lab.JRE,
        "java",
        "-Xmx256m",
        "-cp",
        lab.CP,
        lab.PACKAGE + "ConsumerPairProbe",
    ]
    for client in clients:
        lab.docker("run", "-d", "--name", lab.name(client), *options, *java, "receive")
        lab.containers.append(lab.name(client))
    lab.wait(
        "two active consumers", lambda: all("READY" in lab.logs(c) for c in clients)
    )

    def send(prefix, count):
        def publish():
            result = lab.run(
                "docker",
                "run",
                "--rm",
                *options,
                *java,
                "send",
                prefix,
                str(count),
                check=False,
            )
            if result.returncode == 0:
                return True
            if (
                "ClientImpl.startUp" in result.stdout
                and "response-code=40402" in result.stdout
            ):
                print(memory, "producer waiting for topic route", flush=True)
                return False
            raise RuntimeError(result.stdout[-3500:])

        lab.wait("producer route and publication", publish, 150)

    def acks():
        return Counter(
            line[4:]
            for c in clients
            for line in lab.logs(c).splitlines()
            if line.startswith("ACK ")
        )

    def deadlines():
        return [
            sum(
                line.startswith("ERROR ") and "DEADLINE_EXCEEDED" in line
                for line in lab.logs(c).splitlines()
            )
            for c in clients
        ]

    time.sleep(45)
    send("healthy", 16)
    lab.wait("healthy delivery", lambda: len(acks()) == 16)
    normal = deadlines()
    print(memory, "normal deadlines", normal, flush=True)

    lab.docker("pause", lab.name(proxy))
    try:
        lab.wait(
            "three consecutive receive deadlines on both clients",
            lambda: all(a - b >= 3 for a, b in zip(deadlines(), normal)),
            120,
        )
        interrupted = deadlines()
        print(memory, "observed paused proxy", interrupted, flush=True)
    finally:
        lab.docker("unpause", lab.name(proxy))
    send("recovered", 16)
    lab.wait("delivery after proxy recovery", lambda: len(acks()) == 32)
    send("fault", 1)
    old = lab.wait(
        "before ACK",
        lambda: next((c for c in clients if "BEFORE_ACK" in lab.logs(c)), None),
    )
    lab.docker("kill", lab.name(old))
    new = next(c for c in clients if c != old)
    lab.wait(
        "redelivery to surviving process", lambda: "ACK fault0" in lab.logs(new), 180
    )
    if "RECEIVE fault0 2" not in lab.logs(new) or acks() != Counter(
        ["fault0"] + [f"{p}{i}" for p in ("healthy", "recovered") for i in range(16)]
    ):
        raise RuntimeError("Unexpected delivery counts")
    print(
        memory,
        "paused proxy deadlines",
        [a - b for a, b in zip(interrupted, normal)],
        "acknowledged=33 redelivery=1,2",
        flush=True,
    )


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="durable-consumers-") as scratch:
        folder = Path(scratch).resolve()
        lab.compile_probes(folder)
        lab.docker("network", "create", lab.PROJECT)
        try:
            for memory in ("256m", "1g"):
                try:
                    exercise(folder, memory)
                except Exception:
                    for role in ("broker", "proxy", "a", "b"):
                        container = lab.name(memory + "-" + role)
                        print(
                            container,
                            lab.run(
                                "docker",
                                "inspect",
                                "-f",
                                "{{json .State}}",
                                container,
                                check=False,
                            ).stdout,
                            lab.run(
                                "docker", "logs", "--tail", "12", container, check=False
                            ).stdout,
                            flush=True,
                        )
                        if role == "broker":
                            print(
                                lab.run(
                                    "docker",
                                    "exec",
                                    container,
                                    "sh",
                                    "-c",
                                    "tail -30 ~/logs/rocketmqlogs/store.log ~/logs/rocketmqlogs/broker.log",
                                    check=False,
                                ).stdout,
                                flush=True,
                            )
                    raise
                finally:
                    if lab.containers:
                        lab.run("docker", "rm", "-f", "-v", *reversed(lab.containers))
                        lab.containers.clear()
        finally:
            lab.docker("network", "rm", lab.PROJECT)
