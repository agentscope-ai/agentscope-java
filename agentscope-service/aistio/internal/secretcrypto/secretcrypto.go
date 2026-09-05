// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

// Package secretcrypto provides the shared at-rest encryption primitive for
// control-plane secrets that must be recoverable by an authorized user.
package secretcrypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"fmt"
	"io"
)

// DeriveKey derives a 32-byte AES key from a deployment-managed master secret.
func DeriveKey(master string) []byte {
	sum := sha256.Sum256([]byte(master))
	return sum[:]
}

// Encrypt uses AES-GCM and prefixes the generated nonce to the ciphertext.
func Encrypt(key, plaintext, additionalData []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err = io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}
	return gcm.Seal(nonce, nonce, plaintext, additionalData), nil
}

// Decrypt verifies the optional additional authenticated data before
// returning plaintext.
func Decrypt(key, ciphertext, additionalData []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if len(ciphertext) < gcm.NonceSize() {
		return nil, fmt.Errorf("ciphertext too short")
	}
	nonce := ciphertext[:gcm.NonceSize()]
	return gcm.Open(nil, nonce, ciphertext[gcm.NonceSize():], additionalData)
}
