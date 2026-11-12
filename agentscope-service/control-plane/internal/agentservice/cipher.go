// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package agentservice

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
)

// Cipher encrypts frozen runtime configurations and command input at rest.
// Associated data prevents copying a ciphertext into another Session or Turn.
type Cipher struct{ gcm cipher.AEAD }

func NewCipher(masterKey string) (*Cipher, error) {
	if len(masterKey) < 32 {
		return nil, fmt.Errorf("runtime vault master key must contain at least 32 bytes")
	}
	key := sha256.Sum256([]byte(masterKey))
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &Cipher{gcm: gcm}, nil
}

func (c *Cipher) encode(identity string, value any) ([]byte, error) {
	if c == nil {
		return nil, fmt.Errorf("runtime vault master key is not configured")
	}
	raw, err := json.Marshal(value)
	if err != nil {
		return nil, err
	}
	encoded := make([]byte, 1+c.gcm.NonceSize())
	encoded[0] = 1
	if _, err = io.ReadFull(rand.Reader, encoded[1:]); err != nil {
		return nil, err
	}
	return c.gcm.Seal(encoded, encoded[1:], raw, []byte(identity)), nil
}

func (c *Cipher) decode(identity string, encoded []byte, value any) error {
	if c == nil {
		return fmt.Errorf("runtime vault master key is not configured")
	}
	if len(encoded) < 1+c.gcm.NonceSize()+c.gcm.Overhead() || encoded[0] != 1 {
		return fmt.Errorf("invalid runtime ciphertext")
	}
	raw, err := c.gcm.Open(nil, encoded[1:1+c.gcm.NonceSize()], encoded[1+c.gcm.NonceSize():], []byte(identity))
	if err != nil {
		return fmt.Errorf("runtime ciphertext authentication failed")
	}
	return decodeJSON(raw, value)
}
