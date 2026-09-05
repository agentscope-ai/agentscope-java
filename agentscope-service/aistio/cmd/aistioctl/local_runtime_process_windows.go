//go:build windows

package main

import (
	"os"
	"os/exec"
)

func configureDetachedProcess(command *exec.Cmd) {}

func localProcessAlive(pid int) bool {
	process, err := os.FindProcess(pid)
	if err != nil {
		return false
	}
	return process.Signal(os.Signal(nil)) == nil
}

func terminateLocalProcess(process *os.Process) error {
	return process.Kill()
}
