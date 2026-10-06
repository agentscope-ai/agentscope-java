package store

import (
	"context"
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

type ApplicationRepository interface {
	Create(context.Context, *model.Application) (*model.Application, error)
	Get(context.Context, uuid.UUID) (*model.Application, error)
	List(context.Context, string, string) ([]*model.Application, error)
	Update(context.Context, *model.Application, int64) (*model.Application, error)
}
