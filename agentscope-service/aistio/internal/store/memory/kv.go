package memory

import (
	"context"
	"encoding/json"
	"sort"
	"strings"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type kvRepo struct{ s *Store }

func kvKey(tenant, nsPath, key string) string {
	return tenant + "\x00" + nsPath + "\x00" + key
}

func (r *kvRepo) Get(_ context.Context, tenant, nsPath, key string) (*store.KVItem, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	item, ok := r.s.kv[kvKey(tenant, nsPath, key)]
	if !ok {
		return nil, store.ErrNotFound
	}
	return cloneKVItem(item), nil
}

func (r *kvRepo) Put(_ context.Context, tenant, nsPath, key string, value json.RawMessage) (int64, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	k := kvKey(tenant, nsPath, key)
	var ver int64 = 1
	if prev, ok := r.s.kv[k]; ok {
		ver = prev.Version + 1
	}
	r.s.kv[k] = &store.KVItem{
		Key:     key,
		Value:   append(json.RawMessage(nil), value...),
		Version: ver,
		NsPath:  nsPath,
	}
	return ver, nil
}

func (r *kvRepo) PutIfVersion(_ context.Context, tenant, nsPath, key string, value json.RawMessage, expectedVersion int64) (int64, bool, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	k := kvKey(tenant, nsPath, key)
	prev, ok := r.s.kv[k]

	if expectedVersion == 0 {
		if ok {
			return prev.Version, false, nil
		}
		r.s.kv[k] = &store.KVItem{
			Key:     key,
			Value:   append(json.RawMessage(nil), value...),
			Version: 1,
			NsPath:  nsPath,
		}
		return 1, true, nil
	}

	if !ok {
		return 0, false, store.ErrNotFound
	}
	if prev.Version != expectedVersion {
		return prev.Version, false, nil
	}
	newVer := prev.Version + 1
	r.s.kv[k] = &store.KVItem{
		Key:     key,
		Value:   append(json.RawMessage(nil), value...),
		Version: newVer,
		NsPath:  nsPath,
	}
	return newVer, true, nil
}

func (r *kvRepo) Delete(_ context.Context, tenant, nsPath, key string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	delete(r.s.kv, kvKey(tenant, nsPath, key))
	return nil
}

func (r *kvRepo) Search(_ context.Context, tenant, nsPath string, limit, offset int) ([]*store.KVItem, error) {
	if limit <= 0 {
		limit = 100
	}
	if offset < 0 {
		offset = 0
	}
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()

	prefix := tenant + "\x00"
	var matched []*store.KVItem
	childPrefix := nsPath + store.NamespacePathSeparator
	for k, item := range r.s.kv {
		if !strings.HasPrefix(k, prefix) {
			continue
		}
		rest := k[len(prefix):]
		sep := strings.IndexByte(rest, 0)
		if sep < 0 {
			continue
		}
		itemNs := rest[:sep]
		if itemNs != nsPath && !strings.HasPrefix(itemNs, childPrefix) {
			continue
		}
		matched = append(matched, cloneKVItem(item))
	}
	sort.Slice(matched, func(i, j int) bool {
		if matched[i].Key != matched[j].Key {
			return matched[i].Key < matched[j].Key
		}
		return matched[i].NsPath < matched[j].NsPath
	})
	if offset >= len(matched) {
		return []*store.KVItem{}, nil
	}
	matched = matched[offset:]
	if len(matched) > limit {
		matched = matched[:limit]
	}
	return matched, nil
}

func cloneKVItem(item *store.KVItem) *store.KVItem {
	cp := *item
	if item.Value != nil {
		cp.Value = append(json.RawMessage(nil), item.Value...)
	}
	return &cp
}
