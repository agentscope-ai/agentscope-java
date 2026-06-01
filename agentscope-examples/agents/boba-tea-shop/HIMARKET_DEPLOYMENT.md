# HiMarket Deployment and Usage Guide

## Overview

HiMarket is an AI capability marketplace for publishing and distributing various Agents in the AgentScope multi-agent system.

### HiMarket Features

- **Agent Marketplace**: One-click listing of Agents registered in Nacos, developers can browse and subscribe to various Agent services
- **Admin Console**: Unified management of Agent products, permission configuration, and subscription approval
- **Developer Portal**: Developers can discover, subscribe to, and use Agent capabilities in the portal
- **Observability**: Monitor Agent invocation success rate, latency, and other runtime metrics

HiMarket shares the same Nacos instance with the AgentScope multi-agent system, enabling unified management and exposure of Agents.

---

## Configure Helm Values

Edit `himarket-helm/values.yaml` to configure HiMarket:

```yaml
# HiMarket image configuration
server:
  image:
    hub: registry.cn-hangzhou.aliyuncs.com/agentscope
    repository: himarket-server-auto-init
    tag: "latest"

# MySQL configuration (optional: use built-in or external)
mysql:
  enabled: true  # Use built-in MySQL

# Nacos configuration (import AgentScope's Nacos)
nacos:
  enabled: true  # Enable Nacos import
  name: agentscope-nacos
  serverUrl: http://agentscope-nacos:8848  # Keep consistent with the Nacos address configured earlier
  username: nacos
  password: nacos
```

For more configuration options, please refer to [himarket-helm/README.md](himarket-helm/README.md)

---

## Deploy to K8s

```bash
# Create namespace (if not already created)
kubectl create namespace mse

# Deploy AgentScope multi-agent system (must be deployed first)
helm install agentscope helm/ --namespace mse

# Deploy HiMarket AI capability marketplace
# Import AgentScope's Nacos into HiMarket for unified Agent management
helm install himarket himarket-helm/ --namespace mse

# Check deployment status
kubectl get pods -n mse
kubectl get services -n mse
```

---

## Using HiMarket

### 1. Access HiMarket Admin Console

Access the HiMarket admin console via `kubectl port-forward`:

```bash
# Forward admin console service
kubectl port-forward svc/himarket-admin 5174:80 -n mse

# Browser access
# http://localhost:5174
```

If using LoadBalancer or NodePort:

```bash
# Get HiMarket service address
kubectl get svc -n mse | grep himarket

# LoadBalancer: http://<external-ip>:80
# NodePort: http://<node-ip>:<node-port>
```

### 2. Login with Admin Account

HiMarket automatically creates an admin account during deployment:

**Default Admin Account**:
- Username: `admin`
- Password: `admin`

**Custom Admin Account**:

To modify the default admin account, configure it in `himarket-helm/values.yaml`:

```yaml
autoInit:
  # Admin account configuration
  admin:
    username: your-admin    # Custom username
    password: your-password # Custom password
```

### 3. View Imported Nacos

1. Login to admin console
2. Navigate to "Service Management" → "Nacos Instances"
3. Confirm that AgentScope's Nacos instance has been automatically imported (configured during deployment)
4. Click on the Nacos instance to view all registered Agent services

### 4. List an Agent

1. In the admin console, go to "API Products"
2. Click "Create Product" and select an Agent service from Nacos (e.g., `supervisor-agent`, `business-sub-agent`, etc.)
3. Configure product information, documentation, permissions, etc.
4. Save and publish to the developer portal

### 5. List an MCP Server

In addition to MCP Servers automatically imported during deployment, users can also list their own MCP Servers registered in Nacos.

**Listing Process** (similar to Agent listing):

1. **Register MCP Server to Nacos**
   - Register your MCP Server to the Nacos instance shared with HiMarket
   - Ensure the MCP Server is running properly and can be discovered by Nacos

2. **List in Admin Console**
   - Go to HiMarket admin console
   - Navigate to "API Products" → "Create Product"
   - Select your MCP Server service from the service list
   - Configure product information:
     - Product name and description
     - Description of tools provided by the MCP Server
     - API documentation and usage examples
     - Access permission configuration
   - Save and publish to developer portal

3. **Developer Usage**
   - Developers browse MCP Server products in the portal
   - After subscription, integrate into AgentScope applications
   - Call MCP Server tools via MCP protocol

**Notes**:
- HiMarket automatically discovers all MCP Servers registered in Nacos
- Supports listing services with standard MCP protocol
- Different access permissions and subscription policies can be configured for different MCP Servers

### 6. Developer Subscription

HiMarket automatically creates a demo developer account during deployment, which can be used directly:

**Default Developer Account**:
- Username: `demo`
- Password: `demo123`

**Usage Flow**:
1. Login to HiMarket developer portal with the default account
2. Browse published Agent products
3. Create an application and subscribe to Agents
4. Obtain credentials and integrate into your own applications

**Custom Developer Account**:

To modify the default developer account, configure it in `himarket-helm/values.yaml`:

```yaml
autoInit:
  # Developer account configuration
  developer:
    username: your-developer  # Custom username
    password: your-password   # Custom password
```

Or set via Helm command line:

```bash
helm install himarket himarket-helm/ \
  --set autoInit.developer.username=mydev \
  --set autoInit.developer.password=mypassword123
```

**Notes**:
- HiMarket shares the same Nacos instance with AgentScope
- All Agents registered in Nacos will be automatically discovered by HiMarket
- HiMarket enables unified management and exposure of these Agent capabilities

---

