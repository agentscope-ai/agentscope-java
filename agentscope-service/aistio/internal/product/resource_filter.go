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
