package product

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

const (
	ctxUserID   = "userId"
	ctxUsername = "username"
	ctxRoles    = "roles"
)

func (s *Server) jwtMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		path := c.Request.URL.Path
		if path == "/api/auth/login" ||
			path == "/actuator/health" ||
			path == "/healthz" ||
			strings.HasPrefix(path, "/api/internal/") {
			c.Next()
			return
		}
		if !strings.HasPrefix(path, "/api/") {
			c.Next()
			return
		}
		auth := c.GetHeader("Authorization")
		if !strings.HasPrefix(auth, "Bearer ") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing bearer token"})
			return
		}
		claims, err := parseToken(s.cfg.JWTSecret, strings.TrimPrefix(auth, "Bearer "))
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
			return
		}
		c.Set(ctxUserID, claims.Subject)
		c.Set(ctxUsername, claims.Username)
		c.Set(ctxRoles, claims.Roles)
		c.Next()
	}
}

func (s *Server) internalMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if !strings.HasPrefix(c.Request.URL.Path, "/api/internal/") {
			c.Next()
			return
		}
		tok := c.GetHeader("X-Builder-Internal-Token")
		if s.cfg.InternalToken == "" || tok != s.cfg.InternalToken {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid internal token"})
			return
		}
		if u := c.GetHeader("X-Builder-Internal-User"); u != "" {
			c.Set(ctxUserID, u)
		}
		c.Next()
	}
}

func currentUserID(c *gin.Context) string {
	v, _ := c.Get(ctxUserID)
	s, _ := v.(string)
	return s
}

func currentUsername(c *gin.Context) string {
	v, _ := c.Get(ctxUsername)
	s, _ := v.(string)
	return s
}

func currentRoles(c *gin.Context) []string {
	v, _ := c.Get(ctxRoles)
	roles, _ := v.([]string)
	return roles
}
