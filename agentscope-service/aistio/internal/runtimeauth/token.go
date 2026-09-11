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

package runtimeauth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

const (
	Prefix           = "asrh_"
	EnrollmentPrefix = "asre_"
)

type Claims struct {
	HostKey   string `json:"hostKey"`
	Tenant    string `json:"tenant"`
	Namespace string `json:"namespace"`
	ExpiresAt int64  `json:"expiresAt"`
}

type EnrollmentClaims struct {
	Tenant    string `json:"tenant"`
	Namespace string `json:"namespace"`
	ExpiresAt int64  `json:"expiresAt"`
}

type Manager struct {
	Secret []byte
	TTL    time.Duration
}

func (m Manager) Mint(hostKey, tenant, namespace string, now time.Time) (string, Claims, error) {
	if len(m.Secret) < 32 {
		return "", Claims{}, fmt.Errorf("runtime credential secret must contain at least 32 bytes")
	}
	if strings.TrimSpace(hostKey) == "" || strings.TrimSpace(tenant) == "" || strings.TrimSpace(namespace) == "" {
		return "", Claims{}, fmt.Errorf("host key, tenant, and namespace are required")
	}
	ttl := m.TTL
	if ttl <= 0 {
		ttl = 30 * 24 * time.Hour
	}
	claims := Claims{HostKey: hostKey, Tenant: tenant, Namespace: namespace, ExpiresAt: now.Add(ttl).Unix()}
	payload, err := json.Marshal(claims)
	if err != nil {
		return "", Claims{}, err
	}
	encoded := base64.RawURLEncoding.EncodeToString(payload)
	signature := m.sign(encoded)
	return Prefix + encoded + "." + base64.RawURLEncoding.EncodeToString(signature), claims, nil
}

// MintEnrollment creates a short-lived bootstrap credential whose only
// authority is enrolling Runtime Hosts into the embedded tenant/namespace.
func (m Manager) MintEnrollment(tenant, namespace string, now time.Time) (string, EnrollmentClaims, error) {
	if len(m.Secret) < 32 {
		return "", EnrollmentClaims{}, fmt.Errorf("runtime credential secret must contain at least 32 bytes")
	}
	if strings.TrimSpace(tenant) == "" || strings.TrimSpace(namespace) == "" {
		return "", EnrollmentClaims{}, fmt.Errorf("tenant and namespace are required")
	}
	claims := EnrollmentClaims{Tenant: tenant, Namespace: namespace, ExpiresAt: now.Add(15 * time.Minute).Unix()}
	payload, err := json.Marshal(claims)
	if err != nil {
		return "", EnrollmentClaims{}, err
	}
	encoded := base64.RawURLEncoding.EncodeToString(payload)
	signature := m.signEnrollment(encoded)
	return EnrollmentPrefix + encoded + "." + base64.RawURLEncoding.EncodeToString(signature), claims, nil
}

func (m Manager) VerifyEnrollment(token string, now time.Time) (EnrollmentClaims, error) {
	if len(m.Secret) < 32 || !strings.HasPrefix(token, EnrollmentPrefix) {
		return EnrollmentClaims{}, fmt.Errorf("invalid runtime enrollment token")
	}
	parts := strings.Split(strings.TrimPrefix(token, EnrollmentPrefix), ".")
	if len(parts) != 2 {
		return EnrollmentClaims{}, fmt.Errorf("invalid runtime enrollment token")
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil || !hmac.Equal(signature, m.signEnrollment(parts[0])) {
		return EnrollmentClaims{}, fmt.Errorf("invalid runtime enrollment token")
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return EnrollmentClaims{}, fmt.Errorf("invalid runtime enrollment token")
	}
	var claims EnrollmentClaims
	if json.Unmarshal(payload, &claims) != nil || claims.Tenant == "" || claims.Namespace == "" {
		return EnrollmentClaims{}, fmt.Errorf("invalid runtime enrollment token")
	}
	if claims.ExpiresAt <= now.Unix() {
		return EnrollmentClaims{}, fmt.Errorf("runtime enrollment token expired")
	}
	return claims, nil
}

func (m Manager) Verify(token string, now time.Time) (Claims, error) {
	if len(m.Secret) < 32 || !strings.HasPrefix(token, Prefix) {
		return Claims{}, fmt.Errorf("invalid runtime credential")
	}
	claims, encoded, signature, err := parse(token)
	if err != nil || !hmac.Equal(signature, m.sign(encoded)) {
		return Claims{}, fmt.Errorf("invalid runtime credential")
	}
	if claims.ExpiresAt <= now.Unix() {
		return Claims{}, fmt.Errorf("runtime credential expired")
	}
	return claims, nil
}

// ParseUnverified returns the routing scope embedded in a Runtime Host
// credential. It is intended only for local client configuration; the control
// plane must still call Verify before trusting these claims.
func ParseUnverified(token string) (Claims, error) {
	claims, _, _, err := parse(token)
	return claims, err
}

func parse(token string) (Claims, string, []byte, error) {
	if !strings.HasPrefix(token, Prefix) {
		return Claims{}, "", nil, fmt.Errorf("invalid runtime credential")
	}
	parts := strings.Split(strings.TrimPrefix(token, Prefix), ".")
	if len(parts) != 2 {
		return Claims{}, "", nil, fmt.Errorf("invalid runtime credential")
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return Claims{}, "", nil, fmt.Errorf("invalid runtime credential")
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return Claims{}, "", nil, fmt.Errorf("invalid runtime credential")
	}
	var claims Claims
	if json.Unmarshal(payload, &claims) != nil || claims.HostKey == "" || claims.Tenant == "" || claims.Namespace == "" {
		return Claims{}, "", nil, fmt.Errorf("invalid runtime credential")
	}
	return claims, parts[0], signature, nil
}

func (m Manager) sign(payload string) []byte {
	mac := hmac.New(sha256.New, m.Secret)
	_, _ = mac.Write([]byte(payload))
	return mac.Sum(nil)
}

func (m Manager) signEnrollment(payload string) []byte {
	return m.sign("runtime-enrollment:" + payload)
}
