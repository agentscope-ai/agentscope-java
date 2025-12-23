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

