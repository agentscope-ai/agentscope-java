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
package product

import "github.com/gin-gonic/gin"

// Resource filters are supplied by the authenticated namespace boundary and
// applied in SQL before count/limit/offset. nil means a trusted service caller.
func SetResourceFilter(c *gin.Context, ids []string) {
	if ids == nil {
		ids = []string{}
	}
	c.Set("resourceAllowedIDs", ids)
}
func resourceFilter(c *gin.Context) (bool, []string) {
	v, ok := c.Get("resourceAllowedIDs")
	if !ok {
		return false, []string{}
	}
	ids, _ := v.([]string)
	return true, ids
}
