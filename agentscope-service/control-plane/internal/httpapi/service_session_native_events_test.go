// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//     http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package httpapi

import (
	"encoding/json"
	"testing"
)

func TestNativeSessionEventEnvelopeRejectsForeignAndUnframedData(t *testing.T) {
	for _, value := range []string{
		`{"data":[{"session_id":"other","id":"event","type":"item.completed","cursor":"next"}],"next_cursor":"next"}`,
		`{"data":[{"session_id":"session","id":"event","type":"item.completed\nevent: forged","cursor":"next"}],"next_cursor":"next"}`,
		`{"data":[{"session_id":"session","id":"event","type":"item.completed"}],"next_cursor":"next"}`,
		`{"data":[],"next_cursor":"same","has_more":true}`,
		`{"data":[],"has_more":false}`,
	} {
		if _, err := decodeNativeSessionEvents("session", json.RawMessage(value), "same"); err == nil {
			t.Fatalf("invalid envelope accepted: %s", value)
		}
	}
	page, err := decodeNativeSessionEvents("session", json.RawMessage(`{"data":[{"schema_version":1,"session_id":"session","turn_id":"native-turn","id":"fact","type":"item.completed","cursor":"next","data":{"item_id":"native-item"}}],"next_cursor":"next","has_more":false}`), "same")
	if err != nil || page.Data[0]["turn_id"] != "native-turn" || page.Data[0]["id"] != "fact" || page.Data[0]["cursor"] != "next" {
		t.Fatalf("native identity changed: %+v %v", page, err)
	}
	if _, err = decodeNativeSessionEvents("session", json.RawMessage(`{"data":[],"next_cursor":"same","has_more":false}`), "same"); err != nil {
		t.Fatalf("idle page rejected: %v", err)
	}
}
