package artifact

import (
	"context"
	"io"
	"strings"
	"testing"
)

func TestLocalProviderIntegrityAndTraversal(t *testing.T) {
	provider := &LocalProvider{Root: t.TempDir()}
	info, err := provider.Put(context.Background(), "tenant/ns/object", strings.NewReader("shared result"))
	if err != nil || info.Size != 13 || !strings.HasPrefix(info.Checksum, "sha256:") {
		t.Fatalf("put: info=%+v err=%v", info, err)
	}
	reader, opened, err := provider.Open(context.Background(), "tenant/ns/object")
	if err != nil || opened != info {
		t.Fatalf("open: info=%+v err=%v", opened, err)
	}
	defer reader.Close()
	data, _ := io.ReadAll(reader)
	if string(data) != "shared result" {
		t.Fatalf("unexpected payload %q", data)
	}
	if _, err := provider.Put(context.Background(), "../../escape", strings.NewReader("bad")); err == nil {
		t.Fatal("expected traversal key to fail")
	}
}
