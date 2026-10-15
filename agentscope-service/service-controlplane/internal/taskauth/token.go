// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package taskauth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
)

type Manager struct {
	Secret []byte
	TTL    time.Duration
}

type Claims struct {
	TaskID     uuid.UUID
	AttemptID  uuid.UUID
	Generation int64
	ExpiresAt  time.Time
}

// AttemptClaims are backend-scoped reporting credentials. They intentionally
// use a different signed payload shape from task tokens, so the two token
// classes cannot be substituted for one another.
type AttemptClaims struct {
	AttemptID  uuid.UUID
	Generation int64
	Backend    string
	Target     string
	ExpiresAt  time.Time
}

func (m Manager) Mint(taskID uuid.UUID, now time.Time) (string, error) {
	return m.MintScoped(taskID, uuid.Nil, 0, now)
}

// MintScoped binds a task token to the currently active physical attempt.
func (m Manager) MintScoped(taskID, attemptID uuid.UUID, generation int64, now time.Time) (string, error) {
	if taskID == uuid.Nil || len(m.Secret) < 16 {
		return "", fmt.Errorf("task token requires a task id and a secret of at least 16 bytes")
	}
	ttl := m.TTL
	if ttl <= 0 {
		ttl = time.Hour
	}
	payload := taskID.String() + "." + attemptID.String() + "." +
		strconv.FormatInt(generation, 10) + "." + strconv.FormatInt(now.Add(ttl).Unix(), 10)
	mac := hmac.New(sha256.New, m.Secret)
	_, _ = mac.Write([]byte(payload))
	return base64.RawURLEncoding.EncodeToString([]byte(payload)) + "." +
		base64.RawURLEncoding.EncodeToString(mac.Sum(nil)), nil
}

func (m Manager) Verify(token string, taskID uuid.UUID, now time.Time) error {
	actual, err := m.VerifyAndExtract(token, now)
	if err != nil {
		return err
	}
	if actual != taskID {
		return fmt.Errorf("task token is not scoped to this task")
	}
	return nil
}

func (m Manager) VerifyAndExtract(token string, now time.Time) (uuid.UUID, error) {
	claims, err := m.VerifyClaims(token, now)
	if err != nil {
		return uuid.Nil, err
	}
	return claims.TaskID, nil
}

func (m Manager) VerifyClaims(token string, now time.Time) (Claims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 2 || len(m.Secret) < 16 {
		return Claims{}, fmt.Errorf("invalid task token")
	}
	payloadBytes, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return Claims{}, fmt.Errorf("invalid task token")
	}
	payload := string(payloadBytes)
	payloadParts := strings.Split(payload, ".")
	if len(payloadParts) != 4 {
		return Claims{}, fmt.Errorf("invalid task token")
	}
	taskID, err := uuid.Parse(payloadParts[0])
	if err != nil {
		return Claims{}, fmt.Errorf("invalid task token")
	}
	attemptID, err := uuid.Parse(payloadParts[1])
	if err != nil {
		return Claims{}, fmt.Errorf("invalid task token")
	}
	generation, err := strconv.ParseInt(payloadParts[2], 10, 64)
	if err != nil {
		return Claims{}, fmt.Errorf("invalid task token")
	}
	expires, err := strconv.ParseInt(payloadParts[3], 10, 64)
	if err != nil || !now.Before(time.Unix(expires, 0)) {
		return Claims{}, fmt.Errorf("task token expired")
	}
	want, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return Claims{}, fmt.Errorf("invalid task token")
	}
	mac := hmac.New(sha256.New, m.Secret)
	_, _ = mac.Write([]byte(payload))
	if !hmac.Equal(want, mac.Sum(nil)) {
		return Claims{}, fmt.Errorf("invalid task token")
	}
	return Claims{TaskID: taskID, AttemptID: attemptID, Generation: generation,
		ExpiresAt: time.Unix(expires, 0)}, nil
}

// MintAttempt binds reporting authority to one immutable backend target and
// one dispatch generation.
func (m Manager) MintAttempt(attemptID uuid.UUID, generation int64, backend, target string, now time.Time) (string, error) {
	if attemptID == uuid.Nil || generation <= 0 || backend == "" || target == "" || len(m.Secret) < 16 {
		return "", fmt.Errorf("attempt token requires attempt, generation, backend, target and a secret")
	}
	ttl := m.TTL
	if ttl <= 0 {
		ttl = time.Hour
	}
	encodedTarget := base64.RawURLEncoding.EncodeToString([]byte(target))
	payload := "attempt." + attemptID.String() + "." + strconv.FormatInt(generation, 10) + "." +
		backend + "." + encodedTarget + "." + strconv.FormatInt(now.Add(ttl).Unix(), 10)
	mac := hmac.New(sha256.New, m.Secret)
	_, _ = mac.Write([]byte(payload))
	return base64.RawURLEncoding.EncodeToString([]byte(payload)) + "." +
		base64.RawURLEncoding.EncodeToString(mac.Sum(nil)), nil
}

func (m Manager) VerifyAttempt(token string, attemptID uuid.UUID, generation int64, backend, target string, now time.Time) error {
	claims, err := m.VerifyAttemptClaims(token, now)
	if err != nil {
		return err
	}
	if claims.AttemptID != attemptID || claims.Generation != generation || claims.Backend != backend || claims.Target != target {
		return fmt.Errorf("attempt token does not match the persisted dispatch target")
	}
	return nil
}

func (m Manager) VerifyAttemptClaims(token string, now time.Time) (AttemptClaims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 2 || len(m.Secret) < 16 {
		return AttemptClaims{}, fmt.Errorf("invalid attempt token")
	}
	payloadBytes, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return AttemptClaims{}, fmt.Errorf("invalid attempt token")
	}
	payload := string(payloadBytes)
	fields := strings.Split(payload, ".")
	if len(fields) != 6 || fields[0] != "attempt" {
		return AttemptClaims{}, fmt.Errorf("invalid attempt token")
	}
	attemptID, err := uuid.Parse(fields[1])
	if err != nil {
		return AttemptClaims{}, fmt.Errorf("invalid attempt token")
	}
	generation, err := strconv.ParseInt(fields[2], 10, 64)
	if err != nil {
		return AttemptClaims{}, fmt.Errorf("invalid attempt token")
	}
	targetBytes, err := base64.RawURLEncoding.DecodeString(fields[4])
	if err != nil {
		return AttemptClaims{}, fmt.Errorf("invalid attempt token")
	}
	expires, err := strconv.ParseInt(fields[5], 10, 64)
	if err != nil || !now.Before(time.Unix(expires, 0)) {
		return AttemptClaims{}, fmt.Errorf("attempt token expired")
	}
	want, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return AttemptClaims{}, fmt.Errorf("invalid attempt token")
	}
	mac := hmac.New(sha256.New, m.Secret)
	_, _ = mac.Write([]byte(payload))
	if !hmac.Equal(want, mac.Sum(nil)) {
		return AttemptClaims{}, fmt.Errorf("invalid attempt token")
	}
	return AttemptClaims{AttemptID: attemptID, Generation: generation, Backend: fields[3], Target: string(targetBytes), ExpiresAt: time.Unix(expires, 0)}, nil
}
