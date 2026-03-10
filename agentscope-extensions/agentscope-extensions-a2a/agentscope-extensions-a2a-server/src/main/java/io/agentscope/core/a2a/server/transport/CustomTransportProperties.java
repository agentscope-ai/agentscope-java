package io.agentscope.core.a2a.server.transport;

/**
 * Interface for custom transport properties that can be converted to standard TransportProperties.
 */
public interface CustomTransportProperties {
    /**
     * Converts this custom properties object to standard TransportProperties.
     *
     * @return the TransportProperties representation of this custom properties.
     */
    TransportProperties toTransportProperties();

    /**
     * Sets the deployment properties.
     * @param deploymentProperties the deployment properties.
     */
    void setDeploymentProperties(DeploymentProperties deploymentProperties);

    /**
     * Whether this transport is enabled.
     * @return true if enabled, false otherwise.
     */
    boolean isEnabled();
}
