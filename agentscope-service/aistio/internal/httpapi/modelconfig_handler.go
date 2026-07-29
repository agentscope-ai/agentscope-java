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

func (s *Server) createModelConfig(c *gin.Context) {
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var spec v1alpha1.ModelConfigSpec
	if err := c.ShouldBindJSON(&spec); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	name := c.Query("name")
	if name == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "name query parameter required"})
		return
	}

	mc := &v1alpha1.ModelConfig{
		ObjectMeta: metav1.ObjectMeta{
			Name:      name,
			Namespace: namespace,
		},
		Spec: spec,
	}

	if err := s.client.Create(c.Request.Context(), mc); err != nil {
		if errors.IsAlreadyExists(err) {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "modelconfig already exists"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusCreated, mc)
}

func (s *Server) listModelConfigs(c *gin.Context) {
	namespace := c.DefaultQuery("namespace", "")
	limit := parseLimit(c, 100)
	continueToken := c.Query("continue")

	var list v1alpha1.ModelConfigList
	opts := []client.ListOption{}
	if namespace != "" {
		opts = append(opts, client.InNamespace(namespace))
	}
	if limit > 0 {
		opts = append(opts, client.Limit(int64(limit)))
	}
	if continueToken != "" {
		opts = append(opts, client.Continue(continueToken))
	}
	if err := s.client.List(c.Request.Context(), &list, opts...); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	resp := gin.H{"items": list.Items}
	if list.Continue != "" {
		resp["metadata"] = ListMetadata{Continue: list.Continue}
	}
	c.JSON(http.StatusOK, resp)
}

func (s *Server) getModelConfig(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var mc v1alpha1.ModelConfig
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &mc); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "modelconfig not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, mc)
}

func (s *Server) patchModelConfig(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var mc v1alpha1.ModelConfig
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &mc); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "modelconfig not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	var patch v1alpha1.ModelConfigSpec
	if err := c.ShouldBindJSON(&patch); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	if patch.Provider != "" {
		mc.Spec.Provider = patch.Provider
	}
	if patch.Model != "" {
		mc.Spec.Model = patch.Model
	}
	if patch.Options != nil {
		mc.Spec.Options = patch.Options
	}

	if err := s.client.Update(c.Request.Context(), &mc); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, mc)
}

func (s *Server) deleteModelConfig(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var mc v1alpha1.ModelConfig
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &mc); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "modelconfig not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	if err := s.client.Delete(c.Request.Context(), &mc); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.Status(http.StatusNoContent)
}
