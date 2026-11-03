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

// Package artifact separates shared bytes from collaboration metadata.
package artifact

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

type ObjectInfo struct {
	Size     int64
	Checksum string
}

// Provider is the object-storage SPI used by isolated runtimes. StorageKey is
// opaque outside the provider; local absolute paths never enter the protocol.
type Provider interface {
	Name() string
	Put(ctx context.Context, key string, src io.Reader) (ObjectInfo, error)
	Open(ctx context.Context, key string) (io.ReadCloser, ObjectInfo, error)
	Stat(ctx context.Context, key string) (ObjectInfo, error)
	Delete(ctx context.Context, key string) error
}

type LocalProvider struct{ Root string }

func (p *LocalProvider) Name() string { return "local-object-store" }

func (p *LocalProvider) objectPath(key string) (string, error) {
	root, err := filepath.Abs(p.Root)
	if err != nil {
		return "", err
	}
	clean := filepath.Clean(filepath.FromSlash(key))
	if clean == "." || filepath.IsAbs(clean) || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("invalid artifact storage key")
	}
	path := filepath.Join(root, clean)
	if path != root && !strings.HasPrefix(path, root+string(filepath.Separator)) {
		return "", fmt.Errorf("artifact storage key escapes provider root")
	}
	return path, nil
}

func (p *LocalProvider) Put(ctx context.Context, key string, src io.Reader) (ObjectInfo, error) {
	path, err := p.objectPath(key)
	if err != nil {
		return ObjectInfo{}, err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return ObjectInfo{}, err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".artifact-*")
	if err != nil {
		return ObjectInfo{}, err
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()
	hash := sha256.New()
	written, copyErr := io.Copy(io.MultiWriter(tmp, hash), &contextReader{ctx: ctx, reader: src})
	closeErr := tmp.Close()
	if copyErr != nil {
		return ObjectInfo{}, copyErr
	}
	if closeErr != nil {
		return ObjectInfo{}, closeErr
	}
	if err := os.Chmod(tmpName, 0o640); err != nil {
		return ObjectInfo{}, err
	}
	if err := os.Rename(tmpName, path); err != nil {
		return ObjectInfo{}, err
	}
	return ObjectInfo{Size: written, Checksum: "sha256:" + hex.EncodeToString(hash.Sum(nil))}, nil
}

func (p *LocalProvider) Open(ctx context.Context, key string) (io.ReadCloser, ObjectInfo, error) {
	path, err := p.objectPath(key)
	if err != nil {
		return nil, ObjectInfo{}, err
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, ObjectInfo{}, err
	}
	info, err := checksumFile(ctx, file)
	if err != nil {
		_ = file.Close()
		return nil, ObjectInfo{}, err
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		_ = file.Close()
		return nil, ObjectInfo{}, err
	}
	return file, info, nil
}

func (p *LocalProvider) Stat(ctx context.Context, key string) (ObjectInfo, error) {
	reader, info, err := p.Open(ctx, key)
	if reader != nil {
		_ = reader.Close()
	}
	return info, err
}

func (p *LocalProvider) Delete(_ context.Context, key string) error {
	path, err := p.objectPath(key)
	if err != nil {
		return err
	}
	err = os.Remove(path)
	if os.IsNotExist(err) {
		return nil
	}
	return err
}

func checksumFile(ctx context.Context, file *os.File) (ObjectInfo, error) {
	hash := sha256.New()
	size, err := io.Copy(hash, &contextReader{ctx: ctx, reader: file})
	if err != nil {
		return ObjectInfo{}, err
	}
	return ObjectInfo{Size: size, Checksum: "sha256:" + hex.EncodeToString(hash.Sum(nil))}, nil
}

type contextReader struct {
	ctx    context.Context
	reader io.Reader
}

func (r *contextReader) Read(buf []byte) (int, error) {
	select {
	case <-r.ctx.Done():
		return 0, r.ctx.Err()
	default:
		return r.reader.Read(buf)
	}
}
