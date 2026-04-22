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

# MySQL with initialization script
# Based on Anolis MySQL 8.0.30
# Multi-architecture support: linux/amd64, linux/arm64

FROM anolis-registry.cn-zhangjiakou.cr.aliyuncs.com/openanolis/mysql:8.0.30-8.6

# Set metadata
LABEL maintainer="AgentScope Team"
LABEL description="MySQL 8.0 with AgentScope multi-agent demo initialization"
LABEL version="8.0.30"

# Copy MySQL configuration for UTF-8 support
COPY my.cnf /etc/my.cnf

# Install gettext for envsubst command
RUN yum install -y gettext && yum clean all

# Copy initialization SQL template and shell script to auto-execute directory
# The shell script will process environment variables and execute the SQL
COPY init.sql.template /docker-entrypoint-initdb.d/
COPY init-db.sh /docker-entrypoint-initdb.d/

# Make the shell script executable
RUN chmod +x /docker-entrypoint-initdb.d/init-db.sh

# The entrypoint and command are inherited from base image
# MySQL will use MYSQL_ROOT_PASSWORD environment variable to initialize
# Environment variables for database configuration:
# - DB_NAME: Database name (default: multi-agent-demo)
# - DB_USERNAME: Database user (default: multi_agent_demo)
# - DB_PASSWORD: Database password (default: multi_agent_demo@321)

