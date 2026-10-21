// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package runtimeportal

import (
	"bytes"
	"encoding/json"
	"testing"
)

func TestRuntimePayloadEncryptionKeepsIdentityAndExactNumbers(t *testing.T) {
	key, err := NewCipher("runtime-fixture-master-key-0123456789")
	if err != nil {
		t.Fatal(err)
	}
	value := map[string]any{"providerToken": "private-fixture-token", "fraction": json.Number("1.0E-7"), "number": json.Number("9007199254740993")}
	a, err := key.encode("session:one", value)
	if err != nil {
		t.Fatal(err)
	}
	b, err := key.encode("session:one", value)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Equal(a, b) || bytes.Contains(a, []byte("private-fixture-token")) {
		t.Fatal("payload was not encrypted with a fresh nonce")
	}
	var decoded map[string]any
	if err = key.decode("session:one", a, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded["fraction"] != json.Number("1.0E-7") || decoded["number"] != json.Number("9007199254740993") {
		t.Fatalf("numeric payload changed=%+v", decoded)
	}
	if err = key.decode("session:two", a, &decoded); err == nil {
		t.Fatal("ciphertext was reusable under a different identity")
	}
	a[len(a)-1] ^= 1
	if err = key.decode("session:one", a, &decoded); err == nil {
		t.Fatal("corrupt ciphertext accepted")
	}
	if _, err = NewCipher("short"); err == nil {
		t.Fatal("short master key accepted")
	}
}
