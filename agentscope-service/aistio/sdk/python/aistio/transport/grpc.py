# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""ASDP gRPC 传输（数据面 → 控制面上行推送；下行收 ConfigPush / SessionCommand）。

后台线程驱动 ``Connect`` 双向流：发送走有界队列（best-effort，满则丢弃并
计数），断线自动重连（指数退避，上限 30s）。旁路原则：上报失败静默忽略，
不影响 Agent 主路径。
"""
from __future__ import annotations

import queue
import threading
from typing import Callable, Iterable, List, Optional

import grpc

from .._util import now_ms
from ..proto import asdp_pb2, asdp_pb2_grpc

#: 上行发送队列容量（本地降级有界缓冲）。
SEND_QUEUE_SIZE = 256

#: 空闲心跳间隔（秒）。
HEARTBEAT_INTERVAL = 15.0

#: 重连退避初值 / 上限（秒）。
BACKOFF_INITIAL = 0.5
BACKOFF_MAX = 30.0


class GrpcTransport:
    """ASDP 双向流客户端。"""

    def __init__(
        self,
        addr: str,
        *,
        agent_name: str,
        namespace: str = "default",
        instance_id: str = "",
        sdk_version: str = "",
        capabilities: Iterable[str] = (),
        session_affinity: str = "",
    ) -> None:
        self._addr = addr
        self._agent_name = agent_name
        self._namespace = namespace
        self._instance_id = instance_id
        self._sdk_version = sdk_version
        self._capabilities: List[str] = list(capabilities)
        self._session_affinity = session_affinity

        self._send_q: "queue.Queue[Optional[asdp_pb2.Upstream]]" = queue.Queue(
            maxsize=SEND_QUEUE_SIZE
        )
        self._stop = threading.Event()
        self._connected = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._channel: Optional[grpc.Channel] = None
        self._dropped = 0
        self._on_session_command: Optional[Callable[[str, str, bytes], None]] = None
        self._on_config_push: Optional[Callable[[int, str, bytes, str], None]] = None
        #: 握手成功后控制面回报的版本号。
        self.control_plane_version = ""

    # ─── 生命周期 ───

    def start(self) -> None:
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._run, name="aistio-asdp", daemon=True)
        self._thread.start()

    def stop(self, timeout: float = 5.0) -> None:
        self._stop.set()
        try:
            self._send_q.put_nowait(None)  # 唤醒发送生成器退出
        except queue.Full:
            pass
        # 主动关闭 channel：阻塞在连接/Recv 上的流立即退出，stop 不等超时。
        channel = self._channel
        if channel is not None:
            try:
                channel.close()
            except Exception:
                pass
        if self._thread is not None:
            self._thread.join(timeout)
            self._thread = None

    @property
    def connected(self) -> bool:
        return self._connected.is_set()

    @property
    def dropped(self) -> int:
        """因队列满被丢弃的上行消息数。"""
        return self._dropped

    def wait_connected(self, timeout: float) -> bool:
        return self._connected.wait(timeout)

    # ─── 下行回调 ───

    def set_session_command_handler(self, fn: Callable[[str, str, bytes], None]) -> None:
        """``fn(session_id, command, params)``。"""
        self._on_session_command = fn

    def set_config_push_handler(self, fn: Callable[[int, str, bytes, str], None]) -> None:
        """``fn(config_type, version, resources, nonce)``；异常 → ConfigAck(accepted=False)。"""
        self._on_config_push = fn

    # ─── 上行发送（best-effort）───

    def _enqueue(self, msg: asdp_pb2.Upstream) -> bool:
        try:
            self._send_q.put_nowait(msg)
            return True
        except queue.Full:
            self._dropped += 1
            return False

    def _meta(self) -> "asdp_pb2.UpstreamMeta":
        return asdp_pb2.UpstreamMeta(
            agent_name=self._agent_name,
            instance_id=self._instance_id,
            namespace=self._namespace,
            timestamp=now_ms(),
        )

    def report_sessions(self, snapshots: Iterable["asdp_pb2.SessionSnapshot"]) -> bool:
        return self._enqueue(
            asdp_pb2.Upstream(
                meta=self._meta(),
                session_report=asdp_pb2.SessionReport(sessions=list(snapshots)),
            )
        )

    def report_events(self, events: Iterable["asdp_pb2.SessionEventMsg"]) -> bool:
        return self._enqueue(
            asdp_pb2.Upstream(
                meta=self._meta(), event_report=asdp_pb2.EventReport(events=list(events))
            )
        )

    def report_context(self, report: "asdp_pb2.ContextReport") -> bool:
        return self._enqueue(asdp_pb2.Upstream(meta=self._meta(), context_report=report))

    def report_inventory(self, report: "asdp_pb2.InventoryReport") -> bool:
        return self._enqueue(asdp_pb2.Upstream(meta=self._meta(), inventory=report))

    def send_config_ack(
        self,
        config_type: int,
        version: str,
        nonce: str,
        accepted: bool = True,
        reject_reason: str = "",
    ) -> bool:
        return self._enqueue(
            asdp_pb2.Upstream(
                meta=self._meta(),
                config_ack=asdp_pb2.ConfigAck(
                    config_type=config_type,
                    version=version,
                    nonce=nonce,
                    accepted=accepted,
                    reject_reason=reject_reason,
                ),
            )
        )

    # ─── 后台连接循环 ───

    def _outgoing(self) -> Iterable[asdp_pb2.Upstream]:
        # 首条消息必须是握手 ConnectRequest。
        yield asdp_pb2.Upstream(
            meta=self._meta(),
            connect=asdp_pb2.ConnectRequest(
                runtime=self._runtime(),
                sdk_version=self._sdk_version,
                capabilities=self._capabilities,
                session_affinity=self._session_affinity,
            ),
        )
        last_heartbeat = 0.0
        while not self._stop.is_set():
            try:
                msg = self._send_q.get(timeout=1.0)
            except queue.Empty:
                now = now_ms()
                if now - last_heartbeat >= HEARTBEAT_INTERVAL * 1000:
                    last_heartbeat = now
                    yield asdp_pb2.Upstream(
                        meta=self._meta(), heartbeat=asdp_pb2.Heartbeat(timestamp=now)
                    )
                continue
            if msg is None:
                return
            yield msg

    @staticmethod
    def _runtime() -> str:
        import platform

        return f"python-{platform.python_version()}"

    def _run(self) -> None:
        backoff = BACKOFF_INITIAL
        while not self._stop.is_set():
            channel = grpc.insecure_channel(self._addr)
            self._channel = channel
            try:
                stub = asdp_pb2_grpc.AgentDataPlaneServiceStub(channel)
                responses = stub.Connect(iter(self._outgoing()))
                for down in responses:
                    if self._stop.is_set():
                        return
                    self._handle_downstream(down)
                    backoff = BACKOFF_INITIAL  # 成功收流后重置退避
            except grpc.RpcError:
                pass
            except Exception:
                pass
            finally:
                self._connected.clear()
                self._channel = None
                try:
                    channel.close()
                except Exception:
                    pass
            self._stop.wait(backoff)
            backoff = min(backoff * 2, BACKOFF_MAX)

    def _handle_downstream(self, down: "asdp_pb2.Downstream") -> None:
        kind = down.WhichOneof("payload")
        try:
            if kind == "connect_ack":
                ack = down.connect_ack
                if ack.accepted:
                    self.control_plane_version = ack.control_plane_version
                    self._connected.set()
            elif kind == "session_cmd":
                cmd = down.session_cmd
                if self._on_session_command is not None:
                    self._on_session_command(cmd.session_id, cmd.command, cmd.params)
            elif kind == "config_push":
                push = down.config_push
                accepted, reason = True, ""
                if self._on_config_push is not None:
                    try:
                        self._on_config_push(
                            push.config_type, push.version, push.resources, push.nonce
                        )
                    except Exception as exc:  # 配置应用失败 → NACK
                        accepted, reason = False, str(exc)
                self.send_config_ack(push.config_type, push.version, push.nonce, accepted, reason)
            # heartbeat / team_event 下行目前无需处理。
        except Exception:
            pass  # 旁路原则：任何处理异常都不扩散
