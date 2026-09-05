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

const Prefix = "asrh_"

type Claims struct {
	HostKey   string `json:"hostKey"`
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

func (m Manager) Verify(token string, now time.Time) (Claims, error) {
	if len(m.Secret) < 32 || !strings.HasPrefix(token, Prefix) {
		return Claims{}, fmt.Errorf("invalid runtime credential")
	}
	parts := strings.Split(strings.TrimPrefix(token, Prefix), ".")
	if len(parts) != 2 {
		return Claims{}, fmt.Errorf("invalid runtime credential")
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil || !hmac.Equal(signature, m.sign(parts[0])) {
		return Claims{}, fmt.Errorf("invalid runtime credential")
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return Claims{}, fmt.Errorf("invalid runtime credential")
	}
	var claims Claims
	if json.Unmarshal(payload, &claims) != nil || claims.HostKey == "" || claims.Tenant == "" || claims.Namespace == "" {
		return Claims{}, fmt.Errorf("invalid runtime credential")
	}
	if claims.ExpiresAt <= now.Unix() {
		return Claims{}, fmt.Errorf("runtime credential expired")
	}
	return claims, nil
}

func (m Manager) sign(payload string) []byte {
	mac := hmac.New(sha256.New, m.Secret)
	_, _ = mac.Write([]byte(payload))
	return mac.Sum(nil)
}
