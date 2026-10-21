# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Durable per-execution sandbox journal. Control RPCs never run a long tool inline."""
import base64
import codecs
import hashlib
import json
import os
import io
import shutil
import tarfile
from pathlib import Path
import signal
import subprocess
import sys
import time


def atomic(path, value):
    tmp = path.with_name(path.name + "." + str(os.getpid()) + ".tmp")
    with tmp.open("w") as out:
        json.dump(value, out)
        out.flush()
        os.fsync(out.fileno())
    os.replace(tmp, path)


def identity(pid):
    try:
        stat = Path("/proc/" + str(pid) + "/stat").read_text().split(")", 1)[1].split()
        return [stat[19], Path("/proc/sys/kernel/random/boot_id").read_text().strip()]
    except (FileNotFoundError, ProcessLookupError):
        birth = subprocess.run(["ps", "-p", str(pid), "-o", "lstart="], capture_output=True, text=True).stdout.strip()
        return [birth, "portable-process"] if birth else None


def alive(owner):
    return owner.get("identity") is not None and identity(owner["pid"]) == owner["identity"]


def job_path(root, execution):
    if not execution or len(execution) > 256 or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" for c in execution):
        raise ValueError("Invalid stable execution ID")
    return root / ".managed-tools" / execution


def file_path(root, value):
    path = Path(value)
    path = (path if path.is_absolute() else root / path).resolve()
    if not path.is_relative_to(root):
        raise ValueError("File transfer is outside the sandbox workspace")
    if (root / ".managed-tools") in path.parents:
        raise ValueError("The execution journal is private")
    return path


PRIVATE_DIRS = {".managed-tools", ".managed-checkpoints"}
MAX_SNAPSHOT_BYTES = 32 * 1024 * 1024
MAX_WORKSPACE_BYTES = 256 * 1024 * 1024


def workspace_entries(root):
    values = {}
    total = 0
    for directory, names, files in os.walk(root, followlinks=False):
        names[:] = sorted(name for name in names if Path(directory) != root or name not in PRIVATE_DIRS)
        for name in sorted(names + files):
            path = Path(directory) / name
            if Path(directory) == root and name in PRIVATE_DIRS:
                continue
            if path.is_symlink() or not (path.is_file() or path.is_dir()):
                raise ValueError("Workspace snapshots require regular files and directories")
            relative = path.relative_to(root).as_posix()
            if len(relative) > 2048 or len(values) >= 4096:
                raise ValueError("Workspace snapshot exceeds its entry limits")
            info = path.stat()
            mode = info.st_mode & 0o777
            if path.is_dir():
                values[relative] = {"kind": "directory", "mode": mode}
            else:
                if info.st_size > 64 * 1024 * 1024:
                    raise ValueError("Workspace file exceeds 64 MiB")
                total += info.st_size
                if total > MAX_WORKSPACE_BYTES:
                    raise ValueError("Workspace snapshot exceeds 256 MiB")
                digest = hashlib.sha256()
                with path.open("rb") as source:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        digest.update(chunk)
                if (info.st_size, info.st_mtime_ns, info.st_ino) != (path.stat().st_size, path.stat().st_mtime_ns, path.stat().st_ino):
                    raise ValueError("Workspace changed during snapshot")
                values[relative] = {"kind": "file", "mode": mode, "size": info.st_size, "sha256": digest.hexdigest()}
    return values


def snapshot_workspace(root, job):
    before = workspace_entries(root)
    archive = job / "workspace.tar.gz"
    with tarfile.open(archive, "w:gz") as output:
        for name, metadata in sorted(before.items()):
            path = root / name
            item = tarfile.TarInfo(name)
            item.mode = metadata["mode"]
            if metadata["kind"] == "directory":
                item.type = tarfile.DIRTYPE
                output.addfile(item)
            else:
                item.size = metadata["size"]
                with path.open("rb") as source:
                    output.addfile(item, source)
            if archive.stat().st_size > MAX_SNAPSHOT_BYTES:
                raise ValueError("Compressed Workspace snapshot exceeds 32 MiB")
    if workspace_entries(root) != before:
        raise ValueError("Workspace changed during snapshot")
    content = archive.read_bytes()
    if len(content) > MAX_SNAPSHOT_BYTES:
        raise ValueError("Compressed Workspace snapshot exceeds 32 MiB")
    return {"content": base64.b64encode(content).decode(), "sha256": hashlib.sha256(content).hexdigest(), "manifest": before}


def restore_workspace(root, job, snapshot):
    content = base64.b64decode(snapshot["content"], validate=True)
    if len(content) > MAX_SNAPSHOT_BYTES or hashlib.sha256(content).hexdigest() != snapshot["sha256"]:
        raise ValueError("Workspace snapshot integrity mismatch")
    expected = snapshot["manifest"]
    if not isinstance(expected, dict) or len(expected) > 4096:
        raise ValueError("Invalid Workspace manifest")
    stage = job / "workspace-stage"
    stage.mkdir()
    seen = set()
    total = 0
    try:
        with tarfile.open(fileobj=io.BytesIO(content), mode="r:gz") as archive:
            for item in archive:
                path = Path(item.name)
                if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] in PRIVATE_DIRS or len(item.name) > 2048:
                    raise ValueError("Workspace archive path is invalid")
                if item.name in seen or len(seen) >= 4096 or not (item.isfile() or item.isdir()):
                    raise ValueError("Workspace archive contains an unsupported entry")
                seen.add(item.name)
                if item.size < 0 or item.size > 64 * 1024 * 1024:
                    raise ValueError("Workspace archive file exceeds its limit")
                total += item.size
                if total > MAX_WORKSPACE_BYTES:
                    raise ValueError("Workspace archive exceeds 256 MiB")
                target = stage / path
                if item.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.extractfile(item) as source, target.open("xb") as output:
                        shutil.copyfileobj(source, output, 1024 * 1024)
                        output.flush()
                        os.fsync(output.fileno())
                if item.isfile():
                    target.chmod(item.mode & 0o777)
        for name, metadata in sorted(expected.items(), key=lambda entry: len(Path(entry[0]).parts), reverse=True):
            if metadata["kind"] == "directory":
                (stage / name).chmod(metadata["mode"])
        if workspace_entries(stage) != expected:
            raise ValueError("Workspace archive differs from its manifest")
        for name, metadata in expected.items():
            if metadata["kind"] == "directory":
                (stage / name).chmod(metadata["mode"] | 0o700)
        current = workspace_entries(root)
        atomic(job / "workspace-expected.json", expected)
        for name, metadata in sorted(current.items(), key=lambda entry: len(Path(entry[0]).parts)):
            if metadata["kind"] == "directory":
                (root / name).chmod(metadata["mode"] | 0o700)
        for name in sorted(current, key=lambda value: len(Path(value).parts), reverse=True):
            if name not in expected or current[name]["kind"] != expected[name]["kind"]:
                path = root / name
                if path.is_dir():
                    path.rmdir()
                else:
                    path.unlink()
        for name, metadata in sorted(expected.items(), key=lambda entry: len(Path(entry[0]).parts)):
            target = root / name
            if metadata["kind"] == "directory":
                target.mkdir(parents=True, exist_ok=True)
                target.chmod(metadata["mode"] | 0o700)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                os.replace(stage / name, target)
            if metadata["kind"] == "file":
                target.chmod(metadata["mode"])
        for name, metadata in sorted(expected.items(), key=lambda entry: len(Path(entry[0]).parts), reverse=True):
            if metadata["kind"] == "directory":
                (root / name).chmod(metadata["mode"])
        if workspace_entries(root) != expected:
            raise ValueError("Workspace restore did not reach the confirmed manifest")
        return {"sha256": snapshot["sha256"], "manifest": expected}
    finally:
        shutil.rmtree(stage, ignore_errors=True)


def perform(root, job, request):
    tool, args = request["tool"], request["arguments"]
    if tool == "workspace.snapshot":
        return "SUCCEEDED", snapshot_workspace(root, job)
    if tool == "workspace.restore":
        return "SUCCEEDED", restore_workspace(root, job, args["snapshot"])
    if tool == "artifact.publish":
        target = file_path(root, args["path"])
        if any(part in PRIVATE_DIRS for part in target.relative_to(root).parts):
            raise ValueError("Private runtime files cannot be published")
        if not target.is_file():
            raise ValueError("Artifact must be a regular file")
        with target.open("rb") as source:
            before = os.fstat(source.fileno())
            content = source.read(16 * 1024 * 1024 + 1)
            after = os.fstat(source.fileno())
        if len(content) > 16 * 1024 * 1024:
            raise ValueError("Artifact exceeds 16 MiB")
        if (before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns):
            raise ValueError("Artifact changed during export")
        return "SUCCEEDED", {"content": base64.b64encode(content).decode(), "size": len(content), "sha256": hashlib.sha256(content).hexdigest()}
    if tool == "fs.upload":
        target = file_path(root, args["path"])
        data = base64.b64decode(args["content"], validate=True)
        target.parent.mkdir(parents=True, exist_ok=True)
        tmp = target.with_name(target.name + ".managed-" + request["execution"])
        with tmp.open("wb") as out:
            out.write(data)
            out.flush()
            os.fsync(out.fileno())
        os.replace(tmp, target)
        return "SUCCEEDED", {"path": args["path"], "error": None}
    if tool == "fs.download":
        target = file_path(root, args["path"])
        if not target.is_file():
            return "FAILED", {"path": args["path"], "content": None, "error": "file_not_found"}
        if target.stat().st_size > 64 * 1024 * 1024:
            raise ValueError("Download exceeds 64 MiB")
        return "SUCCEEDED", {"path": args["path"], "content": base64.b64encode(target.read_bytes()).decode(), "error": None}
    if tool != "sandbox.exec":
        raise ValueError("Unsupported Hands operation")
    timeout = int(args.get("timeout_seconds", 30))
    if timeout < 1 or timeout > 86400:
        raise ValueError("Tool timeout must be 1..86400 seconds")
    with (job / "output").open("wb") as output:
        process = subprocess.Popen(["sh", "-c", args["command"]], cwd=root, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        atomic(job / "process.json", {"pid": process.pid, "identity": identity(process.pid)})
        deadline = time.monotonic() + timeout
        stopped = False
        while process.poll() is None:
            if (job / "cancel").exists() or time.monotonic() >= deadline:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
                stopped = True
                break
            time.sleep(0.1)
        code = process.wait()
    with (job / "output").open("rb") as output:
        captured = output.read(1024 * 1024 + 1)
    result = {"output": captured[:1024 * 1024].decode(errors="replace"), "exitCode": code, "truncated": len(captured) > 1024 * 1024}
    return ("CANCELLED" if stopped and (job / "cancel").exists() else "FAILED" if stopped or code != 0 else "SUCCEEDED"), result


def runner(root, execution):
    job = job_path(root, execution)
    atomic(job / "owner.json", {"pid": os.getpid(), "identity": identity(os.getpid())})
    request = json.loads((job / "request.json").read_text())
    try:
        if (job / "cancel").exists():
            state = "CANCELLED"
            result = ({"error": "cancelled", "message": "Cancelled before export"} if request["tool"] == "artifact.publish"
                      else {"path": request["arguments"].get("path"), "error": "cancelled"} if request["tool"].startswith("fs.")
                      else {"output": "Cancelled before execution", "exitCode": -1, "truncated": False})
        else:
            state, result = perform(root, job, request)
    except Exception as error:
        state = "FAILED"
        if request["tool"] == "artifact.publish":
            result = {"error": "artifact_export_failed", "message": str(error)}
        elif request["tool"].startswith("fs."):
            result = {"path": request["arguments"].get("path"), "error": str(error), "content": None}
        else:
            result = {"output": str(error), "exitCode": -1, "truncated": False}
    output = job / "output"
    atomic(job / "result.json", {"state": state, "result": result, "outputSize": output.stat().st_size if output.exists() else 0})


def output_page(job, request):
    offset = request["offset"]
    if not isinstance(offset, int) or isinstance(offset, bool) or offset < 0:
        raise ValueError("Invalid output cursor")
    result = job / "result.json"
    terminal = result.exists()
    final = json.loads(result.read_text()) if terminal else {}
    output = job / "output"
    available = output.stat().st_size if output.exists() else 0
    size = max(offset, available, final.get("outputSize", available))
    page = {"offset": offset, "nextOffset": offset, "data": "", "size": size, "terminal": terminal, "gap": None}
    limit = 16 * 1024 * 1024
    if terminal and (available < size or offset >= limit) and size > offset:
        page.update(nextOffset=size, gap="output_limit" if offset >= limit else "source_unavailable")
        return page
    if available < offset:
        if terminal:
            page.update(gap="source_truncated")
        return page
    if offset >= limit or available == offset:
        return page
    with output.open("rb") as source:
        source.seek(offset)
        content = source.read(min(16 * 1024, limit - offset))
    # Hold incomplete UTF-8 tails until the next read. Raw bytes remain in the private source journal.
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    decoder.decode(content, final=terminal and offset + len(content) == size)
    buffered = len(decoder.getstate()[0])
    if buffered:
        content = content[:-buffered]
    page.update(nextOffset=offset + len(content), data=base64.b64encode(content).decode())
    return page


def control(request):
    root = Path.cwd().resolve()
    execution = request["execution"]
    job = job_path(root, execution)
    action = request["operation"]
    if action == "start":
        job.parent.mkdir(parents=True, exist_ok=True)
        try:
            job.mkdir()
            atomic(job / "request.json", request)
            code = base64.b64encode(Path(__file__).read_bytes()).decode() if "__file__" in globals() else request["program"]
            process = subprocess.Popen(["python3", "-c", "import base64;exec(base64.b64decode('" + code + "'))", "run", str(root), execution], cwd=root, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            # The runner may not have reached owner.json when the next query arrives.
            # Preserve its process identity without authorizing another launch.
            atomic(job / "launcher.json", {"pid": process.pid, "identity": identity(process.pid)})
        except FileExistsError:
            original = job / "request.json"
            if original.exists():
                saved = json.loads(original.read_text())
                if saved["tool"] != request["tool"] or saved["arguments"] != request["arguments"]:
                    raise ValueError("Stable execution ID reused for different content")
        return {"handle": execution}
    if not job.exists():
        return {"state": "NOT_FOUND"}
    if action == "output":
        return output_page(job, request)
    if action == "cancel":
        (job / "cancel").touch()
    result = job / "result.json"
    if result.exists():
        return json.loads(result.read_text())
    for name in ("owner.json", "launcher.json"):
        owner = job / name
        if owner.exists() and alive(json.loads(owner.read_text())):
            return {"state": "RUNNING"}
    expected = job / "workspace-expected.json"
    if expected.exists():
        saved = json.loads((job / "request.json").read_text())
        if saved["tool"] == "workspace.restore" and workspace_entries(root) == json.loads(expected.read_text()):
            confirmed = {"state": "SUCCEEDED", "result": {"sha256": saved["arguments"]["snapshot"]["sha256"], "manifest": json.loads(expected.read_text())}}
            atomic(result, confirmed)
            return confirmed
    # A launch marker without a living runner/result is an ambiguous outcome, never a rerun.
    return {"state": "UNKNOWN"}


if len(sys.argv) > 1 and sys.argv[1] == "run":
    runner(Path(sys.argv[2]), sys.argv[3])
else:
    try:
        print(json.dumps(control(json.loads(base64.b64decode(sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read())))))
    except Exception as error:
        print(json.dumps({"state": "UNKNOWN", "error": str(error)}))
