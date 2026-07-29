package asdp

import (
	"context"
	"crypto/x509"
	"fmt"
	"strings"

	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/peer"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/internal/version"
)

// ConnectHandler processes incoming Connect handshakes from data plane instances.
type ConnectHandler struct {
	server *Server
}

// NewConnectHandler creates a ConnectHandler.
func NewConnectHandler(server *Server) *ConnectHandler {
	return &ConnectHandler{server: server}
}

// HandleConnect validates a ConnectRequest handshake. It does NOT register the
// connection — registration (with the write channel and stream cancel) is owned
// by service.Connect once the handshake is accepted. This method only validates
// the claimed identity, including binding the mTLS client certificate (when
// present) to the claimed agent/namespace to prevent impersonation.
func (h *ConnectHandler) HandleConnect(ctx context.Context, meta *UpstreamMeta, req *ConnectRequest) *ConnectResponse {
	logger := log.Log.WithName("asdp-connect")

	if meta.AgentName == "" || meta.InstanceId == "" || meta.Namespace == "" {
		return &ConnectResponse{
			Accepted:     false,
			RejectReason: "agentName, instanceId, and namespace are required",
		}
	}

	// When the transport presents a verified client certificate (mTLS), bind it
	// to the claimed identity so an instance cannot impersonate another agent.
	if err := verifyPeerIdentity(ctx, meta); err != nil {
		logger.Info("rejecting handshake: client certificate identity mismatch",
			"agent", meta.AgentName, "namespace", meta.Namespace, "instance", meta.InstanceId, "error", err.Error())
		return &ConnectResponse{
			Accepted:     false,
			RejectReason: err.Error(),
		}
	}

	// Proactively tear down a stale connection for the same instance so its
	// writer goroutine and stream are released before the new one registers.
	if existing, ok := h.server.GetConnection(meta.Namespace, meta.InstanceId); ok {
		logger.Info("reconnecting existing instance",
			"agent", existing.AgentName,
			"instance", meta.InstanceId,
		)
		h.server.UnregisterConnection(meta.Namespace, meta.InstanceId)
	}

	logger.Info("handshake accepted",
		"agent", meta.AgentName,
		"instance", meta.InstanceId,
		"runtime", req.Runtime,
		"sdkVersion", req.SdkVersion,
		"capabilities", req.Capabilities,
	)

	return &ConnectResponse{
		Accepted:            true,
		ControlPlaneVersion: version.Version,
	}
}

// verifyPeerIdentity checks the peer's mTLS client certificate (if any) against
// the claimed agent/namespace. If the connection is not mTLS (no verified client
// certificate), it returns nil: transport authentication is not enforced here and
// is expected to be handled by network policy / a non-mTLS deployment.
func verifyPeerIdentity(ctx context.Context, meta *UpstreamMeta) error {
	cert := peerLeafCert(ctx)
	if cert == nil {
		return nil
	}
	if identityMatchesAgent(cert, meta.Namespace, meta.AgentName) {
		return nil
	}
	return fmt.Errorf("client certificate identity does not authorize agent %q in namespace %q", meta.AgentName, meta.Namespace)
}

// peerLeafCert extracts the verified leaf client certificate from the gRPC peer
// context, or nil when the connection is not mutually authenticated.
func peerLeafCert(ctx context.Context) *x509.Certificate {
	p, ok := peer.FromContext(ctx)
	if !ok || p.AuthInfo == nil {
		return nil
	}
	tlsInfo, ok := p.AuthInfo.(credentials.TLSInfo)
	if !ok {
		return nil
	}
	if len(tlsInfo.State.VerifiedChains) > 0 && len(tlsInfo.State.VerifiedChains[0]) > 0 {
		return tlsInfo.State.VerifiedChains[0][0]
	}
	if len(tlsInfo.State.PeerCertificates) > 0 {
		return tlsInfo.State.PeerCertificates[0]
	}
	return nil
}

// identityMatchesAgent reports whether the certificate authorizes the given
// agent/namespace. Accepted identity encodings:
//   - CommonName or a DNS SAN equal to "<agent>", "<agent>.<namespace>",
//     "<agent>.<namespace>.svc" or "<agent>.<namespace>.svc.cluster.local"
//   - a SPIFFE-style URI SAN ending in "/ns/<namespace>/agent/<agent>"
func identityMatchesAgent(cert *x509.Certificate, namespace, agentName string) bool {
	names := make(map[string]struct{})
	if cert.Subject.CommonName != "" {
		names[cert.Subject.CommonName] = struct{}{}
	}
	for _, d := range cert.DNSNames {
		names[d] = struct{}{}
	}
	for _, candidate := range []string{
		agentName,
		agentName + "." + namespace,
		agentName + "." + namespace + ".svc",
		agentName + "." + namespace + ".svc.cluster.local",
	} {
		if _, ok := names[candidate]; ok {
			return true
		}
	}
	suffix := fmt.Sprintf("/ns/%s/agent/%s", namespace, agentName)
	for _, u := range cert.URIs {
		if strings.HasSuffix(u.String(), suffix) || strings.HasSuffix(u.Path, suffix) {
			return true
		}
	}
	return false
}

// HandleDisconnect handles a data plane instance disconnection (stream closed).
func (h *ConnectHandler) HandleDisconnect(namespace, instanceID string) {
	logger := log.Log.WithName("asdp-connect")
	logger.Info("instance disconnected", "namespace", namespace, "instance", instanceID)
	h.server.UnregisterConnection(namespace, instanceID)
}

// GetInstanceKey returns the routing key for a namespace/instance pair.
func GetInstanceKey(namespace, instanceID string) string {
	return fmt.Sprintf("%s/%s", namespace, instanceID)
}
