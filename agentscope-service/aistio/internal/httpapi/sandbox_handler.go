package httpapi

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

func (s *Server) createSandbox(c *gin.Context) {
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req struct {
		Name     string `json:"name"`
		AgentRef string `json:"agentRef"`
		Session  string `json:"session,omitempty"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	claim := &v1alpha1.SandboxClaim{
		ObjectMeta: metav1.ObjectMeta{
			Name:      req.Name,
			Namespace: namespace,
		},
		Spec: v1alpha1.SandboxClaimSpec{
			AgentRef: v1alpha1.ObjectReference{Name: req.AgentRef},
			SandboxTemplate: v1alpha1.SandboxTemplateSpec{
				Lifecycle: &v1alpha1.SandboxLifecycle{
					ShutdownPolicy: "Delete",
					IdleTimeout:    "30m",
				},
			},
		},
	}

	if req.Session != "" {
		claim.Spec.SessionRef = v1alpha1.ObjectReference{Name: req.Session}
	}

	if err := s.client.Create(c.Request.Context(), claim); err != nil {
		if errors.IsAlreadyExists(err) {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "sandbox claim already exists"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"name":  req.Name,
		"phase": "Pending",
		"agent": req.AgentRef,
	})
}

func (s *Server) listSandboxes(c *gin.Context) {
	namespace := c.DefaultQuery("namespace", "")

	var list v1alpha1.SandboxClaimList
	opts := []client.ListOption{}
	if namespace != "" {
		opts = append(opts, client.InNamespace(namespace))
	}
	if err := s.client.List(c.Request.Context(), &list, opts...); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"items": list.Items})
}

func (s *Server) getSandbox(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var claim v1alpha1.SandboxClaim
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &claim); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "sandbox not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, claim)
}

func (s *Server) deleteSandbox(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var claim v1alpha1.SandboxClaim
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &claim); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "sandbox not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	if err := s.client.Delete(c.Request.Context(), &claim); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.Status(http.StatusNoContent)
}
