# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

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

