FROM ubuntu:22.04

# Prevent interactive prompts during apt installs
ENV DEBIAN_FRONTEND=noninteractive

# Update and install base tooling
RUN apt-get update && apt-get install -y --no-install-recommends \
    # Version control
    git \
    curl \
    wget \
    # Build tools
    build-essential \
    make \
    # Python 3
    python3 \
    python3-pip \
    python3-venv \
    # Node.js (via NodeSource)
    ca-certificates \
    gnupg \
    # JDK 17
    openjdk-17-jdk-headless \
    # Misc utilities
    jq \
    unzip \
    zip \
    ripgrep \
    && rm -rf /var/lib/apt/lists/*

# Install Node.js 20 LTS
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

# Install GitHub CLI
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
    | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg \
    && chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
    | tee /etc/apt/sources.list.d/github-cli.list > /dev/null \
    && apt-get update \
    && apt-get install -y gh \
    && rm -rf /var/lib/apt/lists/*

# Create a non-root workspace user
RUN useradd -m -s /bin/bash agentscope
USER agentscope
WORKDIR /home/agentscope/workspace

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

CMD ["/bin/bash"]
