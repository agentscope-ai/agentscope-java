package dataplane

import (
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/prober"
)

func TestSessionsProbeLikelyTruncated(t *testing.T) {
	if prober.SessionsProbeLikelyTruncated(499) {
		t.Fatal("499 should not be truncated")
	}
	if !prober.SessionsProbeLikelyTruncated(500) {
		t.Fatal("500 should be truncated")
	}
	res := prober.SessionsProbeResult{Sessions: make([]prober.SessionSnapshot, 10), HasMore: true}
	if !res.LikelyTruncated() {
		t.Fatal("HasMore should mark truncated")
	}
}
