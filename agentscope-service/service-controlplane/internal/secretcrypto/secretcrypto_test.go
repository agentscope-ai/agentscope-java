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

package secretcrypto

import (
	"bytes"
	"testing"
)

func TestEncryptDecryptRequiresMatchingAdditionalData(t *testing.T) {
	key := DeriveKey("stable deployment master key")
	ciphertext, err := Encrypt(key, []byte("asep_secret"), []byte("endpoint-a:credential-a"))
	if err != nil {
		t.Fatal(err)
	}
	plaintext, err := Decrypt(key, ciphertext, []byte("endpoint-a:credential-a"))
	if err != nil || !bytes.Equal(plaintext, []byte("asep_secret")) {
		t.Fatalf("decrypt mismatch: plaintext=%q err=%v", plaintext, err)
	}
	if _, err = Decrypt(key, ciphertext, []byte("endpoint-b:credential-a")); err == nil {
		t.Fatal("ciphertext was accepted for another Endpoint")
	}
	tampered := append([]byte(nil), ciphertext...)
	tampered[len(tampered)-1] ^= 1
	if _, err = Decrypt(key, tampered, []byte("endpoint-a:credential-a")); err == nil {
		t.Fatal("tampered ciphertext was accepted")
	}
}
