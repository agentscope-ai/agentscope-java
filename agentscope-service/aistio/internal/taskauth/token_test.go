package taskauth

import (
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestVerifyAndExtractTaskToken(t *testing.T) {
	now := time.Now().UTC()
	manager := Manager{Secret: []byte("0123456789abcdef0123456789abcdef"), TTL: time.Minute}
	taskID := uuid.New()
	token, err := manager.Mint(taskID, now)
	if err != nil {
		t.Fatal(err)
	}
	actual, err := manager.VerifyAndExtract(token, now.Add(time.Second))
	if err != nil || actual != taskID {
		t.Fatalf("extract: id=%s err=%v", actual, err)
	}
	if err := manager.Verify(token, uuid.New(), now); err == nil {
		t.Fatal("token verified for a different task")
	}
	if _, err := manager.VerifyAndExtract(token, now.Add(2*time.Minute)); err == nil {
		t.Fatal("expired token was accepted")
	}
}

func TestAttemptTokenIsFencedAndNotATaskToken(t *testing.T) {
	now := time.Now().UTC()
	manager := Manager{Secret: []byte("0123456789abcdef0123456789abcdef"), TTL: time.Minute}
	attemptID := uuid.New()
	token, err := manager.MintAttempt(attemptID, 3, "external-application", "instance.one", now)
	if err != nil {
		t.Fatal(err)
	}
	if err := manager.VerifyAttempt(token, attemptID, 3, "external-application", "instance.one", now); err != nil {
		t.Fatal(err)
	}
	if err := manager.VerifyAttempt(token, attemptID, 4, "external-application", "instance.one", now); err == nil {
		t.Fatal("stale generation was accepted")
	}
	if _, err := manager.VerifyClaims(token, now); err == nil {
		t.Fatal("attempt token was accepted as a task token")
	}
}
