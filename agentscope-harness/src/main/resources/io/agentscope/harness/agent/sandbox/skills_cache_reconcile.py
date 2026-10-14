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

"""Linux-only descriptor-relative cache cleanup; unsupported kernels fail closed."""

import ctypes
import errno
import json
import os
import platform
import stat
import sys


class OpenHow(ctypes.Structure):
    _fields_ = [("flags", ctypes.c_uint64), ("mode", ctypes.c_uint64), ("resolve", ctypes.c_uint64)]


def reconcile(plan, before_delete=None, unlink=None):
    if sys.platform != "linux" or platform.machine() not in ("x86_64", "aarch64"):
        raise RuntimeError("Cache sync requires Linux x86_64/aarch64 with openat2")
    root = plan["root"]
    if not root.startswith(".skills-cache/") or any(p in ("", ".", "..") for p in root.split("/")):
        raise ValueError("Invalid scoped cache root")
    libc = ctypes.CDLL(None, use_errno=True)
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW
    workspace = os.open(plan["workspace"], flags)
    opened = []

    def open_directory(relative):
        # RESOLVE_NO_XDEV rejects mount crossings, including same-device bind mounts.
        how = OpenHow(flags, 0, 0x01 | 0x04 | 0x08)  # NO_XDEV | NO_SYMLINKS | BENEATH
        fd = libc.syscall(437, workspace, ctypes.c_char_p(os.fsencode(relative)), ctypes.byref(how), ctypes.sizeof(how))
        if fd < 0:
            code = ctypes.get_errno()
            raise OSError(code, os.strerror(code), relative)
        return fd

    def identity(info):
        return info.st_dev, info.st_ino, stat.S_IFMT(info.st_mode)

    def physical(fd):
        return os.readlink("/proc/self/fd/" + str(fd))

    try:
        # Failure to read mount topology is a failure, not an empty mount list.
        absolute = physical(workspace).rstrip("/") + "/" + root
        with open("/proc/self/mountinfo", encoding="utf-8") as mounts:
            for line in mounts:
                mount = line.split()[4]
                for encoded, decoded in (("\\040", " "), ("\\011", "\t"), ("\\012", "\n"), ("\\134", "\\")):
                    mount = mount.replace(encoded, decoded)
                if mount != "/" and (mount == absolute or mount.startswith(absolute + "/") or absolute.startswith(mount.rstrip("/") + "/")):
                    raise RuntimeError("Mount inside synchronized cache: " + mount)
        try:
            scope = open_directory(root)
        except FileNotFoundError:
            # Probe the kernel before accepting an absent root as an empty tree.
            probe = open_directory(".")
            os.close(probe)
            return
        opened.append(scope)
        if physical(scope) != absolute:
            raise RuntimeError("Cache scope moved or aliased")
        entries = {"": identity(os.fstat(scope))}

        def scan(relative, directory):
            for name in sorted(os.listdir(directory)):
                child = name if not relative else relative + "/" + name
                info = os.stat(name, dir_fd=directory, follow_symlinks=False)
                if stat.S_ISLNK(info.st_mode):
                    raise RuntimeError("Symbolic link in synchronized cache: " + child)
                entries[child] = identity(info)
                if stat.S_ISDIR(info.st_mode):
                    fd = open_directory(root + "/" + child)
                    try:
                        if identity(os.fstat(fd)) != entries[child]:
                            raise RuntimeError("Cache changed during validation")
                        scan(child, fd)
                    finally:
                        os.close(fd)

        scan("", scope)
        if not plan["delete"]:
            return
        retained = set()
        for file in plan["files"]:
            if file.startswith(root + "/"):
                relative = file[len(root) + 1:]
                if any(p in ("", ".", "..") for p in relative.split("/")):
                    raise ValueError("Noncanonical payload path")
                retained.add(relative)
                while "/" in relative:
                    relative = relative.rsplit("/", 1)[0]
                    retained.add(relative)
        if before_delete is not None:
            before_delete()
        for relative in sorted(entries, key=lambda p: (p.count("/"), p), reverse=True):
            if not relative or relative in retained:
                continue
            parent, _, name = relative.rpartition("/")
            # Reopen through the workspace descriptor on every mutation, not through old cwd.
            fd = open_directory(root + ("/" + parent if parent else ""))
            try:
                if identity(os.fstat(fd)) != entries[parent] or physical(fd) != absolute + ("/" + parent if parent else ""):
                    raise RuntimeError("Cache parent changed before deletion")
                if identity(os.stat(name, dir_fd=fd, follow_symlinks=False)) != entries[relative]:
                    raise RuntimeError("Cache entry changed before deletion")
                if entries[relative][2] == stat.S_IFDIR:
                    os.rmdir(name, dir_fd=fd)
                elif unlink is not None:
                    unlink(name, fd)
                else:
                    os.unlink(name, dir_fd=fd)
            finally:
                os.close(fd)
    finally:
        for fd in opened:
            os.close(fd)
        os.close(workspace)


if __name__ == "__main__":
    try:
        reconcile(json.load(sys.stdin.buffer))
        print("AGENTSCOPE_CACHE_RECONCILED")
    except Exception as error:
        print("Cache reconciliation refused or failed: " + str(error), file=sys.stderr)
        sys.exit(1)
