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

package product

import (
	"crypto/sha256"
	"encoding/base64"
	"fmt"

	"github.com/spring-ai-alibaba/aistio/internal/secretcrypto"
)

// vaultKey derives a 32-byte AES key from VAULT_MASTER_KEY or jwt-secret.
func vaultKey(master, jwtSecret string) []byte {
	src := master
	if src == "" {
		src = jwtSecret
	}
	return secretcrypto.DeriveKey(src)
}

func encryptAESGCM(key []byte, plaintext string) ([]byte, error) {
	return secretcrypto.Encrypt(key, []byte(plaintext), nil)
}

func decryptAESGCM(key []byte, ciphertext []byte) (string, error) {
	plain, err := secretcrypto.Decrypt(key, ciphertext, nil)
	if err != nil {
		return "", err
	}
	return string(plain), nil
}

func sha256Hex(plaintext string) string {
	sum := sha256.Sum256([]byte(plaintext))
	return fmt.Sprintf("%x", sum)
}

// encodeB64 is available if callers store ciphertext as text.
func encodeB64(b []byte) string {
	return base64.StdEncoding.EncodeToString(b)
}

func decodeB64(s string) ([]byte, error) {
	return base64.StdEncoding.DecodeString(s)
}
