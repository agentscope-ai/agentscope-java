package io.agentscope.core.a2a.server.transport.jsonrpc;

import io.a2a.server.TransportMetadata;
import io.a2a.spec.TransportProtocol;

/**
 * SPI for JSON-RPC transport metadata.
 *
 * <p>Inject transport `JSONRPC` supported into A2A Server to validate agentCard.
 */
public class JsonRpcTransportMetadata implements TransportMetadata {

    @Override
    public String getTransportProtocol() {
        return TransportProtocol.JSONRPC.asString();
    }
}
