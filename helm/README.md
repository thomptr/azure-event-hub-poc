# Helm Charts

This directory contains Helm charts for deploying the Event Hub POC applications to Kubernetes.

## Charts

| Chart | Description |
|-------|-------------|
| `test-api` | Spring Boot REST API that returns random messages |
| `test-consumer` | Kafka consumer that processes messages and saves to SQL Server |
| `wiremock` | WireMock server for mocking API responses and error scenarios |
| `chaos-mesh` | Chaos engineering platform for resilience testing (uses official chart) |

## Prerequisites

- Kubernetes cluster (AKS)
- Helm 3.x installed
- `kubectl` configured to connect to your cluster
- Container images pushed to ACR
- Azure Bicep deployment completed (see `../azure/main.bicep`)

## Using Bicep Outputs with Helm

After deploying the Azure infrastructure with Bicep, you can use the outputs to populate Helm values automatically.

### Option 1: Use the Deploy Script (Recommended)

The `scripts/deploy-helm.sh` script automatically fetches Bicep outputs and passes them to Helm:

```bash
# Deploy all charts with Bicep outputs
./scripts/deploy-helm.sh all

# Or deploy individual components
./scripts/deploy-helm.sh test-api
./scripts/deploy-helm.sh test-consumer

# Generate a values file from Bicep outputs
./scripts/deploy-helm.sh generate-values
```

Environment variables:
```bash
export RESOURCE_GROUP="eventhub-poc-rg"
export NAMESPACE="eventhub-poc"
export SQL_USERNAME="sqladmin"
export SQL_PASSWORD="your-password"
```

### Option 2: Manual with --set Flags

Fetch the Bicep outputs and use them directly:

```bash
# Get deployment outputs
OUTPUTS=$(az deployment group show \
  --resource-group eventhub-poc-rg \
  --name <deployment-name> \
  --query "properties.outputs" -o json)

# Extract values
EVENT_HUB_NS=$(echo $OUTPUTS | jq -r '.eventHubNamespaceName.value')
EVENT_HUB_NAME=$(echo $OUTPUTS | jq -r '.eventHubName.value')
SQL_SERVER=$(echo $OUTPUTS | jq -r '.sqlServerFqdn.value')
SQL_DB=$(echo $OUTPUTS | jq -r '.sqlDatabaseName.value')

# Get Event Hub connection string
EH_CONN_STRING=$(az eventhubs namespace authorization-rule keys list \
  --resource-group eventhub-poc-rg \
  --namespace-name $EVENT_HUB_NS \
  --name "RootManageSharedAccessKey" \
  --query "primaryConnectionString" -o tsv)

# Deploy with values
helm install test-consumer ./test-consumer \
  --namespace eventhub-poc \
  --set config.eventHub.namespace="$EVENT_HUB_NS" \
  --set config.kafka.topic="$EVENT_HUB_NAME" \
  --set config.database.host="$SQL_SERVER" \
  --set config.database.name="$SQL_DB" \
  --set secrets.eventHubConnectionString="$EH_CONN_STRING" \
  --set secrets.sqlUsername="sqladmin" \
  --set secrets.sqlPassword="your-password"
```

### Bicep Output to Helm Value Mapping

| Bicep Output | Helm Value (test-consumer) |
|--------------|----------------------------|
| `eventHubNamespaceName` | `config.eventHub.namespace` |
| `eventHubName` | `config.kafka.topic` |
| `sqlServerFqdn` | `config.database.host` |
| `sqlDatabaseName` | `config.database.name` |
| `acrLoginServer` | `image.repository` (prefix) |

| Bicep Parameter | Helm Value (test-consumer) |
|-----------------|----------------------------|
| `sqlAdminLogin` | `secrets.sqlUsername` |
| `sqlAdminPassword` | `secrets.sqlPassword` |

## Deploy All Charts

### 1. Get AKS Credentials

```bash
az aks get-credentials --resource-group eventhub-poc-rg --name myapp-aks
```

### 2. Create Namespace

```bash
kubectl create namespace eventhub-poc
```

### 3. Deploy WireMock (for error testing)

```bash
helm install wiremock ./wiremock \
  --namespace eventhub-poc
```

### 4. Deploy Test API

```bash
helm install test-api ./test-api \
  --namespace eventhub-poc \
  --set image.tag=v1
```

### 5. Deploy Test Consumer

First, create the secrets file `test-consumer-values.yaml`:

```yaml
config:
  eventHub:
    namespace: "myappeventhubns"
  database:
    host: "myapp-sqlserver.database.windows.net"
    name: "myapp-db"

secrets:
  eventHubConnectionString: "Endpoint=sb://myappeventhubns.servicebus.windows.net/;SharedAccessKeyName=..."
  sqlUsername: "sqladmin"
  sqlPassword: "your-password"
```

Then deploy:

```bash
helm install test-consumer ./test-consumer \
  --namespace eventhub-poc \
  --values test-consumer-values.yaml \
  --set image.tag=v1
```

## Testing Error Scenarios with WireMock

WireMock is pre-configured with error scenario mappings. To test, configure the test-consumer to point to WireMock:

```bash
# Update test-consumer to use WireMock as the API
helm upgrade test-consumer ./test-consumer \
  --namespace eventhub-poc \
  --set config.testApi.url="http://wiremock:8080"
```

### Available Error Endpoints

| Endpoint | Response |
|----------|----------|
| `/api/accounts/error/400` | 400 Bad Request |
| `/api/accounts/error/401` | 401 Unauthorized |
| `/api/accounts/error/403` | 403 Forbidden |
| `/api/accounts/error/404` | 404 Not Found |
| `/api/accounts/error/429` | 429 Too Many Requests |
| `/api/accounts/error/500` | 500 Internal Server Error |
| `/api/accounts/error/502` | 502 Bad Gateway |
| `/api/accounts/error/503` | 503 Service Unavailable |
| `/api/accounts/slow` | 200 OK (5 second delay) |
| `/api/accounts/flaky` | 200 OK (random response) |

### WireMock Admin API

```bash
# Port-forward WireMock admin
kubectl port-forward svc/wiremock 8081:8080 -n eventhub-poc

# List mappings
curl http://localhost:8081/__admin/mappings

# Add new mapping dynamically
curl -X POST http://localhost:8081/__admin/mappings \
  -H "Content-Type: application/json" \
  -d '{
    "request": { "method": "POST", "urlPattern": "/api/accounts/custom" },
    "response": { "status": 418, "body": "I am a teapot" }
  }'
```

## Upgrade Charts

```bash
# Upgrade test-api
helm upgrade test-api ./test-api \
  --namespace eventhub-poc \
  --set image.tag=v2

# Upgrade test-consumer
helm upgrade test-consumer ./test-consumer \
  --namespace eventhub-poc \
  --values test-consumer-values.yaml \
  --set image.tag=v2
```

## Deploy Chaos Mesh (for resilience testing)

```bash
# Add Chaos Mesh repo
helm repo add chaos-mesh https://charts.chaos-mesh.org
helm repo update

# Create namespace
kubectl create namespace chaos-mesh

# Install Chaos Mesh (for AKS with containerd)
helm install chaos-mesh chaos-mesh/chaos-mesh \
  --namespace chaos-mesh \
  --version 2.8.0 \
  --values ./chaos-mesh/values.yaml

# Verify installation
kubectl get pods -n chaos-mesh
```

### Run Chaos Experiments

```bash
# Pod failure test
kubectl apply -f ./chaos-mesh/experiments/pod-failure.yaml

# Network delay test
kubectl apply -f ./chaos-mesh/experiments/network-delay.yaml

# HTTP fault injection
kubectl apply -f ./chaos-mesh/experiments/http-fault.yaml

# Stress test
kubectl apply -f ./chaos-mesh/experiments/stress-test.yaml

# Run full workflow
kubectl apply -f ./chaos-mesh/experiments/workflow-example.yaml
```

### Access Chaos Dashboard

```bash
kubectl port-forward svc/chaos-dashboard 2333:2333 -n chaos-mesh
# Open http://localhost:2333
```

## Uninstall Charts

```bash
helm uninstall test-consumer --namespace eventhub-poc
helm uninstall test-api --namespace eventhub-poc
helm uninstall wiremock --namespace eventhub-poc
helm uninstall chaos-mesh --namespace chaos-mesh
```

## Chart Values Reference

### test-api

| Parameter | Description | Default |
|-----------|-------------|---------|
| `image.repository` | Image repository | `eventhubpocacr.azurecr.io/test-api` |
| `image.tag` | Image tag | `latest` |
| `replicaCount` | Number of replicas | `1` |
| `service.port` | Service port | `8089` |
| `ingress.enabled` | Enable ingress | `true` |

### test-consumer

| Parameter | Description | Default |
|-----------|-------------|---------|
| `image.repository` | Image repository | `eventhubpocacr.azurecr.io/test-consumer` |
| `image.tag` | Image tag | `latest` |
| `config.kafka.topic` | Kafka topic | `myapp-eventhub` |
| `config.testApi.url` | Test API URL | `http://test-api:8089` |
| `config.database.host` | SQL Server host | `` |
| `secrets.eventHubConnectionString` | Event Hub connection string | `` |
| `secrets.sqlUsername` | SQL username | `` |
| `secrets.sqlPassword` | SQL password | `` |

### wiremock

| Parameter | Description | Default |
|-----------|-------------|---------|
| `image.repository` | Image repository | `wiremock/wiremock` |
| `image.tag` | Image tag | `3.3.1` |
| `service.port` | HTTP port | `8080` |
| `wiremock.verbose` | Enable verbose logging | `true` |
| `wiremock.proxy.enabled` | Enable proxy mode | `false` |
| `wiremock.proxy.targetUrl` | Proxy target URL | `` |

