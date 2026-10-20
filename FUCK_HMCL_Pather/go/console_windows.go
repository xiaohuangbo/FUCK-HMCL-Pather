//go:build windows

package main

import (
	"syscall"
)

var (
	kernel32         = syscall.NewLazyDLL("kernel32.dll")
	setConsoleCP     = kernel32.NewProc("SetConsoleCP")
	setConsoleOutput = kernel32.NewProc("SetConsoleOutputCP")
)

func init() {
	// Make Chinese output readable in the legacy Windows console.
	_, _, _ = setConsoleCP.Call(65001)
	_, _, _ = setConsoleOutput.Call(65001)
}
