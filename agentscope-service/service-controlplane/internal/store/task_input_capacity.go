// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package store

import (
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/google/uuid"
)

const MaxTaskInputs = 8192
const MaxTaskInputBytes = (MaxRunGraphBytes-2)/MaxTaskInputs - 1
const taskInputErrorBytes = 1024

var ErrTaskInputCapacity = fmt.Errorf("%w: task input capacity exceeded", ErrResourceLimit)

func CapacityRouteReason(err error) string {
	if errors.Is(err, ErrTaskInputCapacity) {
		return "task_input_capacity"
	}
	return "run_graph_capacity"
}

// Reserve all lifecycle metadata at admission so later acknowledgements,
// failures and completion cannot make an admitted input unreadable.
func ValidateTaskInputCapacity(input model.AgentTaskInput) error {
	at := time.Date(9999, 12, 31, 23, 59, 59, 999999999, time.FixedZone("", 3600))
	id := uuid.Nil
	input.DeliveredAt, input.AcknowledgedAt, input.ProcessedAt = &at, &at, &at
	input.NextAttemptAt, input.CreatedAt, input.ResponseCommentID = &at, at, &id
	input.CommentVersion, input.Sequence, input.Attempts = 1<<63-1, 1<<63-1, 1<<31-1
	input.State = model.TaskInputAcknowledged
	// LastError is a diagnostic preview, capped by its JSON size below.
	input.LastError = strings.Repeat("x", taskInputErrorBytes)
	raw, err := json.Marshal(input)
	if err != nil {
		return err
	}
	if len(raw) > MaxTaskInputBytes {
		return ErrTaskInputCapacity
	}
	return nil
}

func TaskInputsBytes(inputs []model.AgentTaskInput) (int, error) {
	if len(inputs) > MaxTaskInputs {
		return 0, ErrTaskInputCapacity
	}
	bytes := 2
	for _, input := range inputs {
		raw, err := json.Marshal(input)
		if err != nil {
			return 0, err
		}
		bytes += len(raw) + 1
		if bytes > MaxRunGraphBytes {
			return 0, ErrTaskInputCapacity
		}
	}
	return bytes, nil
}

func ValidateTaskInputIDs(ids ...[]uuid.UUID) error {
	count := 0
	for _, list := range ids {
		count += len(list)
		if count > MaxTaskInputs {
			return ErrTaskInputCapacity
		}
	}
	return nil
}

// A diagnostic preview explicitly records shortening and the original hash;
// it is not a truncated user input or an incomplete delivery result.
func TaskInputErrorSummary(message string) string {
	if len(message) <= taskInputErrorBytes {
		raw, _ := json.Marshal(message)
		if len(raw)-2 <= taskInputErrorBytes {
			return message
		}
	}
	digest := sha256.Sum256([]byte(message))
	suffix := fmt.Sprintf(" [diagnostic preview; originalBytes=%d; sha256=%x]", len(message), digest)
	end := min(len(message), taskInputErrorBytes-len(suffix))
	for end > 0 {
		for end > 0 && end < len(message) && !utf8.RuneStart(message[end]) {
			end--
		}
		preview := message[:end] + suffix
		raw, _ := json.Marshal(preview)
		if len(raw)-2 <= taskInputErrorBytes {
			return preview
		}
		end--
	}
	return suffix
}
