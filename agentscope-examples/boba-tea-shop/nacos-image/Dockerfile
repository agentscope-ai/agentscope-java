# Nacos Server with auto password initialization
# Based on Nacos v3.1.1
# Multi-architecture support: linux/amd64, linux/arm64

FROM nacos-registry.cn-hangzhou.cr.aliyuncs.com/nacos/nacos-server:v3.1.1

# Set metadata
LABEL maintainer="AgentScope Team"
LABEL description="Nacos Server v3.1.1 with auto admin password initialization"
LABEL version="3.1.1"

# Set environment variables
ENV PREFER_HOST_MODE=hostname \
    MODE=standalone \
    NACOS_AUTH_IDENTITY_KEY=serverIdentity \
    NACOS_AUTH_IDENTITY_VALUE=security \
    NACOS_AUTH_TOKEN=nacosauthtoken

# Copy initialization script
COPY init-password.sh /home/nacos/init-password.sh

# Make script executable
RUN chmod +x /home/nacos/init-password.sh

# The base image already has an entrypoint that starts Nacos
# We need to run our init script in the background alongside Nacos
# Override the entrypoint to run both
COPY docker-entrypoint.sh /home/nacos/docker-entrypoint.sh
RUN chmod +x /home/nacos/docker-entrypoint.sh

ENTRYPOINT ["/home/nacos/docker-entrypoint.sh"]

