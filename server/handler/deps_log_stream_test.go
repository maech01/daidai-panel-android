package handler

import (
	"bufio"
	"strings"
	"testing"
)

func TestScanTerminalLogEntrySupportsCarriageReturnProgress(t *testing.T) {
	scanner := bufio.NewScanner(strings.NewReader("download 10%\rdownload 20%\r\ndone\nlast"))
	scanner.Split(scanTerminalLogEntry)

	var entries []string
	for scanner.Scan() {
		entries = append(entries, scanner.Text())
	}
	if err := scanner.Err(); err != nil {
		t.Fatalf("scan terminal output: %v", err)
	}
	want := []string{"download 10%", "download 20%", "done", "last"}
	if len(entries) != len(want) {
		t.Fatalf("entries = %#v", entries)
	}
	for i := range want {
		if entries[i] != want[i] {
			t.Fatalf("entry %d = %q, want %q", i, entries[i], want[i])
		}
	}
}
