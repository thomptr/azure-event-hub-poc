# Azure Event Hub POC

This project deploys an Azure infrastructure for applications running in AKS that consume messages from Azure Event Hub and process them via a REST API.

See video for overview : [Loom video](https://www.loom.com/share/b6c2e67ede664193b4f6405d36b6e4d2)

## Applications

| Application | Description |
|-------------|-------------|
| **test-api** | Spring Boot REST API that accepts account requests and returns responses |
| **test-consumer** | Spring Boot Kafka consumer that reads from Event Hub and calls test-api |

## Architecture

See the architecture diagram: [docs/architecture-diagram.svg](docs/architecture-diagram.svg)

### Components

| Resource | Description |
|----------|-------------|
| **Virtual Network** | VNet with AKS and Application Gateway subnets |
| **Azure Kubernetes Service** | Managed Kubernetes cluster with AGIC addon |
| **Application Gateway** | WAF v2 with IP restriction for secure ingress |
| **Azure Container Registry** | Private registry for Docker images |
| **Azure Event Hub** | Message streaming platform (Kafka-compatible) |
| **Azure App Configuration** | Centralized configuration store |
| **Azure SQL Database** | SQL Server Basic tier for transaction persistence |
| **Application Insights** | APM with OpenTelemetry tracing and metrics |
| **Log Analytics Workspace** | Centralized logging for Application Insights |
| **Azure Managed Grafana** | Dashboards for Prometheus metrics visualization |
| **Azure Monitor Workspace** | Prometheus metrics storage for AKS |

### Message Flow

```
Event Hub → test-consumer → test-api → SQL Database
                ↓
         DLQ/Error Topics (on failure)
```

### Network Security

- Only your home router IP can access the Application Gateway (configured via `allowedIp` parameter)
- Application Gateway routes traffic to NGINX Ingress on port 8080
- All Azure services use Managed Identity for secure access (no passwords/keys)


## Prerequisites

- Azure CLI installed and logged in
- Docker installed
- Helm 3.x installed
- Java 17+ and Maven (for building apps)

## Deployment

### 1. Create Resource Group

```bash
az group create --name eventhub-poc-rg --location eastus2
```

### 2. Generate SSH Key (if needed)

```bash
ssh-keygen -t rsa -b 4096 -f ~/.ssh/aks_key -N ""
```

### 3. Update Parameters

```bash
cp azure/parameters.json_example azure/parameters.json
```

Edit `azure/parameters.json` and update:
- `allowedIp`: Your home router's public IP address
- `sshRSAPublicKey`: Your SSH public key (from `~/.ssh/aks_key.pub`)
- `sqlAdminPassword`: A strong password for SQL Server admin
- `grafanaAdminPrincipalId`: Your Azure AD Object ID (for Grafana and SQL Server admin access)

To find your public IP:
```bash
curl ifconfig.me
```

To find your Azure AD Object ID (required for Grafana and SQL portal query editor access):
```bash
az ad signed-in-user show --query id -o tsv
```

### 4. Deploy Infrastructure

> **Note**: You may need to change the `acrName` in `main.bicep` if the name is already taken globally.

```bash
az deployment group create \
  --resource-group eventhub-poc-rg \
  --template-file azure/main.bicep \
  --parameters @azure/parameters.json \
  --parameters location=eastus2 \
  --name main
```

### 5. Get Deployment Outputs

```bash
# Get all outputs
az deployment group show \
  --resource-group eventhub-poc-rg \
  --name main \
  --query "properties.outputs" -o json

# Or individual values
ACR_NAME=$(az deployment group show -g eventhub-poc-rg -n main --query "properties.outputs.acrLoginServer.value" -o tsv)
EVENT_HUB_NS=$(az deployment group show -g eventhub-poc-rg -n main --query "properties.outputs.eventHubNamespaceName.value" -o tsv)
SQL_SERVER=$(az deployment group show -g eventhub-poc-rg -n main --query "properties.outputs.sqlServerFqdn.value" -o tsv)
APP_INSIGHTS_CONN=$(az deployment group show -g eventhub-poc-rg -n main --query "properties.outputs.applicationInsightsConnectionString.value" -o tsv)
```

### 6. Get AKS Credentials

```bash
az aks get-credentials --resource-group eventhub-poc-rg --name myapp-aks
```

## Build and Deploy Applications

### 1. Login to ACR

```bash
az acr login --name eventhubpocacr
```

### 2. Build and Push test-api

```bash
cd apps/test-api

# Build with Maven
./mvnw clean package -DskipTests

# Build Docker image
docker build -t eventhubpocacr.azurecr.io/test-api:v1 .

# Push to ACR
docker push eventhubpocacr.azurecr.io/test-api:v1
```

### 3. Build and Push test-consumer

```bash
cd apps/test-consumer

# Build with Maven
./mvnw clean package -DskipTests

# Build Docker image
docker build -t eventhubpocacr.azurecr.io/test-consumer:v1 .

# Push to ACR
docker push eventhubpocacr.azurecr.io/test-consumer:v1
```

### 4. Build and Push test-producer

```bash
cd apps/test-producer

# Build with Maven
mvn clean package -DskipTests

# Build Docker image
docker build -t eventhubpocacr.azurecr.io/test-producer:v1 .

# Push to ACR
docker push eventhubpocacr.azurecr.io/test-producer:v1
```

### 5. Create Kubernetes Namespace

```bash
# kubectl create namespace eventhub-poc
```

### 6. Get Event Hub Connection String

```bash
EVENT_HUB_CONN=$(az eventhubs namespace authorization-rule keys list \
  --resource-group eventhub-poc-rg \
  --namespace-name $EVENT_HUB_NS \
  --name RootManageSharedAccessKey \
  --query "primaryConnectionString" -o tsv)
```

### 7. Deploy test-api with Helm

```bash
helm upgrade --install test-api ./helm/test-api \
  --namespace eventhub-poc-api \
  --create-namespace \
  --set image.repository=eventhubpocacr.azurecr.io/test-api \
  --set image.tag=v1 \
  --set "applicationInsights.connectionString=$APP_INSIGHTS_CONN"
```

### 8. Deploy test-consumer with Helm

```bash
helm upgrade --install test-consumer ./helm/test-consumer \
  --namespace eventhub-poc-consumer \
  --create-namespace \
  --set image.repository=eventhubpocacr.azurecr.io/test-consumer \
  --set image.tag=v1 \
  --set config.eventHub.namespace=$EVENT_HUB_NS \
  --set config.kafka.topic=myapp-eventhub \
  --set config.database.host=$SQL_SERVER \
  --set config.database.name=myapp-db \
  --set secrets.eventHubConnectionString="$EVENT_HUB_CONN" \
  --set secrets.sqlUsername=sqladmin \
  --set secrets.sqlPassword='YOUR_SQL_PASSWORD' \
  --set "applicationInsights.connectionString=$APP_INSIGHTS_CONN"
```

### 9. Deploy test-producer with Helm

```bash
helm upgrade --install test-producer ./helm/test-producer \
  --namespace eventhub-poc-producer \
  --create-namespace \
  --set image.repository=eventhubpocacr.azurecr.io/test-producer \
  --set image.tag=v1 \
  --set config.eventHub.namespace=${EVENT_HUB_NS}.servicebus.windows.net:9093 \
  --set config.kafka.topic=myapp-eventhub \
  --set config.producer.messagesPerSecond=100 \
  --set secrets.eventHubConnectionString="$EVENT_HUB_CONN" \
  --set "applicationInsights.connectionString=$APP_INSIGHTS_CONN"
```

**Control the producer via REST API:**

```bash
# Start producing messages
kubectl exec -n eventhub-poc-api deploy/test-producer -- curl -X POST http://localhost:8087/api/producer/start

# Check status
kubectl exec -n eventhub-poc-api deploy/test-producer -- curl http://localhost:8087/api/producer/status

# Change rate to 500 msg/sec
kubectl exec -n eventhub-poc-api deploy/test-producer -- curl -X POST "http://localhost:8087/api/producer/rate?messagesPerSecond=500"

# Stop producing
kubectl exec -n eventhub-poc-api deploy/test-producer -- curl -X POST http://localhost:8087/api/producer/stop
```

### Alternative: Use the Deploy Script

The `scripts/deploy-helm.sh` script automates fetching Bicep outputs:

```bash
# Set credentials
export SQL_USERNAME=sqladmin
export SQL_PASSWORD='YOUR_SQL_PASSWORD'

# Deploy all applications
./scripts/deploy-helm.sh all

# Or deploy individually
./scripts/deploy-helm.sh test-api
./scripts/deploy-helm.sh test-consumer
```

## Verify Deployment

```bash
# Check pods are running
kubectl get pods -n eventhub-poc

# Check services
kubectl get svc -n eventhub-poc

# View test-consumer logs
kubectl logs -f deployment/test-consumer -n eventhub-poc

# Test test-api endpoint
kubectl port-forward svc/test-api 8089:8089 -n eventhub-poc
curl -X POST http://localhost:8089/api/accounts \
  -H "Content-Type: application/json" \
  -d '{"firstName":"John","lastName":"Doe","accountNumber":"12345","accountAction":"CREATE"}'
```

## Outputs

After deployment, you'll get:

| Output | Description |
|--------|-------------|
| `aksClusterName` | Name of the AKS cluster |
| `acrLoginServer` | ACR login server URL |
| `eventHubNamespaceName` | Event Hub namespace name |
| `eventHubName` | Event Hub (topic) name |
| `appConfigEndpoint` | App Configuration endpoint |
| `sqlServerFqdn` | SQL Server fully qualified domain name |
| `applicationGatewayFqdn` | Public FQDN to access your app |
| `applicationInsightsConnectionString` | Connection string for OpenTelemetry export |
| `applicationInsightsInstrumentationKey` | Instrumentation key for Application Insights |
| `grafanaEndpoint` | Azure Managed Grafana URL |
| `azureMonitorWorkspaceId` | Azure Monitor Workspace for Prometheus |

## Observability

### Application Insights (Traces)

Both applications export OpenTelemetry traces to Azure Application Insights.

#### View Traces in Azure Portal

1. Go to **Azure Portal** → **Application Insights** → `myapp-appinsights`
2. Click **Transaction search** to see individual requests
3. Click **Application map** to see service dependencies
4. Click **Performance** to view response times and throughput

#### Verify Tracing is Working

Check the application logs for:
```
Azure Application Insights configured - traces will be exported
```

If you see this warning, the connection string isn't set:
```
APPLICATIONINSIGHTS_CONNECTION_STRING not set - traces will not be exported to Azure
```

#### Update Connection String

```bash
# Get the connection string
APP_INSIGHTS_CONN=$(az deployment group show -g eventhub-poc-rg -n main --query "properties.outputs.applicationInsightsConnectionString.value" -o tsv)

# Update test-api
helm upgrade test-api ./helm/test-api --namespace eventhub-poc --set "applicationInsights.connectionString=$APP_INSIGHTS_CONN" --reuse-values

# Update test-consumer
helm upgrade test-consumer ./helm/test-consumer --namespace eventhub-poc --set "applicationInsights.connectionString=$APP_INSIGHTS_CONN" --reuse-values
```

### Prometheus Metrics

Both apps expose Prometheus metrics at `/actuator/prometheus`. The pods are configured with annotations for automatic discovery:

```yaml
prometheus.io/scrape: "true"
prometheus.io/port: "8089"  # or 8088 for test-consumer
prometheus.io/path: "/actuator/prometheus"
```

#### Apply Prometheus Scrape Config

```bash
# Apply the ConfigMap for Azure Monitor Prometheus
kubectl apply -f k8s/prometheus-configmap.yaml

# Upgrade Helm releases to add annotations (if not already deployed with them)
helm upgrade test-api ./helm/test-api --namespace eventhub-poc --reuse-values
helm upgrade test-consumer ./helm/test-consumer --namespace eventhub-poc --reuse-values
```

#### Verify Metrics Endpoint

```bash
# Port-forward and test the prometheus endpoint
kubectl port-forward svc/test-api 8089:8089 -n eventhub-poc
curl http://localhost:8089/actuator/prometheus | head -50
```

### Azure Managed Grafana (Metrics Dashboard)

The infrastructure includes Azure Managed Grafana with Prometheus integration for viewing metrics from both apps in a single dashboard.

#### Access Grafana

```bash
# Get the Grafana URL
GRAFANA_URL=$(az deployment group show -g eventhub-poc-rg -n main --query "properties.outputs.grafanaEndpoint.value" -o tsv)
echo "Grafana URL: $GRAFANA_URL"
```

Open the URL in your browser. Sign in with your Azure AD credentials.

#### Import the Dashboard

1. In Grafana, click **Dashboards** → **Import**
2. Upload the file `grafana/dashboard-spring-boot-apps.json`
3. Select the Azure Monitor Prometheus datasource
4. Click **Import**

The dashboard shows:
- **Overview**: Running pods, request rate, latency, error rate
- **test-api Metrics**: Request rate by endpoint, response time percentiles
- **test-consumer Metrics**: Kafka message rate, consumer lag
- **JVM Metrics**: Heap memory, CPU usage for both apps

#### Grant User Access to Grafana

By default, only the subscription owner has access. To add users:

```bash
# Get Grafana resource ID
GRAFANA_ID=$(az grafana show --name myapp-grafana --resource-group eventhub-poc-rg --query id -o tsv)

# Grant Grafana Admin role to a user
az role assignment create \
  --assignee "user@example.com" \
  --role "Grafana Admin" \
  --scope $GRAFANA_ID
```

Available roles: `Grafana Admin`, `Grafana Editor`, `Grafana Viewer`

## Local Development

### Run test-api locally

```bash
cd apps/test-api
./mvnw spring-boot:run
# API available at http://localhost:8089
```

### Run test-api with WireMock (Docker Compose)

```bash
cd apps/test-api
docker-compose up -d

# test-api at http://localhost:8089
# WireMock at http://localhost:8091
```

### Run test-consumer locally

```bash
cd apps/test-consumer
./mvnw spring-boot:run
# Requires Kafka/Event Hub connection
```

## Clean Up / Tear Down

### Resource Groups Created by Bicep

The deployment creates multiple resource groups:

| Resource Group | Description | Auto-deleted? |
|----------------|-------------|---------------|
| `eventhub-poc-rg` | Main resource group (all primary resources) | Manual |
| `MC_eventhub-poc-rg_myapp-aks_<region>` | AKS managed resources (VMs, disks, NICs) | ✅ Yes (when main RG deleted) |

### Delete All Resources

```bash
# Option 1: Delete main resource group (waits for completion)
# This automatically deletes the MC_* managed resource group
az group delete --name eventhub-poc-rg --yes

# Option 2: Delete in background (faster, non-blocking)
az group delete --name eventhub-poc-rg --yes --no-wait
```

### Verify Cleanup

```bash
# Check if main resource group is deleted
az group exists --name eventhub-poc-rg

# List any remaining related resource groups
az group list --query "[?contains(name, 'eventhub-poc') || contains(name, 'MC_eventhub-poc')].name" -o tsv
```

### Force Clean Up (if automatic deletion fails)

In rare cases, managed resource groups may not be auto-deleted. To force cleanup:

```bash
# Delete any remaining MC_* resource groups manually
az group list --query "[?contains(name, 'MC_eventhub-poc')].name" -o tsv | xargs -I {} az group delete --name {} --yes --no-wait
```

## Cost Estimate (Development/POC Configuration)

| Resource | Estimated Monthly Cost |
|----------|----------------------|
| AKS (1 x Standard_B2ms) | ~$60 |
| Application Gateway WAF v2 | ~$250 |
| Container Registry (Basic) | ~$5 |
| Event Hub (Standard) | ~$22 |
| App Configuration (Free) | $0 |
| SQL Database (Basic, 5 DTU) | ~$5 |
| Application Insights | ~$2-5 (based on data ingestion) |
| Log Analytics Workspace | ~$2-5 (based on data ingestion) |
| Azure Managed Grafana (Standard) | ~$35 |
| Azure Monitor Workspace | ~$0.15/GB ingested |
| **Total** | **~$385/month** |

> **Note**: Application Gateway WAF v2 is the largest cost. For lower costs, consider using Standard_v2 SKU without WAF (~$175/month) by modifying `main.bicep`.

## Security

See [docs/SECURITY.md](docs/SECURITY.md) for handling sensitive information and secrets management.

**Important**: Never commit `azure/parameters.json` - it contains sensitive data and is gitignored.

## Additional Documentation

- [Helm Charts](helm/README.md) - Detailed Helm deployment instructions
- [Chaos Mesh](helm/chaos-mesh/README.md) - Resilience testing with Chaos Mesh
- [Security Guide](docs/SECURITY.md) - Secrets management and security best practices
