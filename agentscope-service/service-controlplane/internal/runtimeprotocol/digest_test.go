// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package runtimeprotocol

import (
	"bytes"
	"encoding/json"
	"testing"
)

func TestCanonicalDigestMatchesJava(t *testing.T) {
	var v struct {
		Input  map[string]any `json:"input"`
		Digest string         `json:"digest"`
	}
	decoder := json.NewDecoder(bytes.NewReader(fixture(t, "canonical-digest")))
	decoder.UseNumber()
	if err := decoder.Decode(&v); err != nil {
		t.Fatal(err)
	}
	actual, err := Digest(v.Input)
	if err != nil || actual != v.Digest {
		t.Fatalf("cross-language digest=%q expected=%q error=%v", actual, v.Digest, err)
	}
}

func TestLifecycleDigestMatchesJava(t *testing.T) {
	var intent RuntimeSessionLifecycle
	var receipt RuntimeLifecycleReceipt
	if err := Decode(bytes.NewReader(fixture(t, "lifecycle")), &intent); err != nil {
		t.Fatal(err)
	}
	if err := Decode(bytes.NewReader(fixture(t, "lifecycle-receipt")), &receipt); err != nil {
		t.Fatal(err)
	}
	actual, err := Digest(intent)
	if err != nil || actual != receipt.PayloadDigest {
		t.Fatalf("lifecycle digest=%s expected=%s err=%v", actual, receipt.PayloadDigest, err)
	}
}
