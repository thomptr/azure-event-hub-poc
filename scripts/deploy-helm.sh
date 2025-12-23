#!/bin/bash
# =============================================================================
# deploy-helm.sh
# Deploys Helm charts using outputs from Azure Bicep deployment
# =============================================================================

set -e

# Configuration
RESOURCE_GROUP="${RESOURCE_GROUP:-eventhub-poc-rg}"
NAMESPACE="${NAMESPACE:-eventhub-poc}"
NAME_PREFIX="${NAME_PREFIX:-myapp}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# =============================================================================
# Get Bicep Deployment Outputs
# =============================================================================
get_bicep_outputs() {
    log_info "Fetching Bicep deployment outputs from resource group: $RESOURCE_GROUP"
    
    # Get the most recent deployment in the resource group
    DEPLOYMENT_NAME=$(az deployment group list \
        --resource-group "$RESOURCE_GROUP" \
        --query "[0].name" -o tsv)
    
    if [ -z "$DEPLOYMENT_NAME" ]; then
        log_error "No deployment found in resource group $RESOURCE_GROUP"
        exit 1
    fi
    
    log_info "Found deployment: $DEPLOYMENT_NAME"
    
    # Fetch all outputs
    OUTPUTS=$(az deployment group show \
        --resource-group "$RESOURCE_GROUP" \
        --name "$DEPLOYMENT_NAME" \
        --query "properties.outputs" -o json)
    
    # Parse outputs
    export ACR_LOGIN_SERVER=$(echo "$OUTPUTS" | jq -r '.acrLoginServer.value // empty')
    export AKS_CLUSTER_NAME=$(echo "$OUTPUTS" | jq -r '.aksClusterName.value // empty')
    export EVENT_HUB_NAMESPACE=$(echo "$OUTPUTS" | jq -r '.eventHubNamespaceName.value // empty')
    export EVENT_HUB_NAME=$(echo "$OUTPUTS" | jq -r '.eventHubName.value // empty')
    export APP_CONFIG_ENDPOINT=$(echo "$OUTPUTS" | jq -r '.appConfigEndpoint.value // empty')
    export SQL_SERVER_FQDN=$(echo "$OUTPUTS" | jq -r '.sqlServerFqdn.value // empty')
    export SQL_DATABASE_NAME=$(echo "$OUTPUTS" | jq -r '.sqlDatabaseName.value // empty')
    export SQL_CONNECTION_STRING=$(echo "$OUTPUTS" | jq -r '.sqlConnectionString.value // empty')
    export APP_GATEWAY_FQDN=$(echo "$OUTPUTS" | jq -r '.applicationGatewayFqdn.value // empty')
    
    log_info "ACR Login Server: $ACR_LOGIN_SERVER"
    log_info "AKS Cluster Name: $AKS_CLUSTER_NAME"
    log_info "Event Hub Namespace: $EVENT_HUB_NAMESPACE"
    log_info "Event Hub Name: $EVENT_HUB_NAME"
    log_info "SQL Server FQDN: $SQL_SERVER_FQDN"
    log_info "SQL Database Name: $SQL_DATABASE_NAME"
}

# =============================================================================
# Get Event Hub Connection String
# =============================================================================
get_eventhub_connection_string() {
    log_info "Fetching Event Hub connection string..."
    
    export EVENT_HUB_CONNECTION_STRING=$(az eventhubs namespace authorization-rule keys list \
        --resource-group "$RESOURCE_GROUP" \
        --namespace-name "$EVENT_HUB_NAMESPACE" \
        --name "RootManageSharedAccessKey" \
        --query "primaryConnectionString" -o tsv)
    
    if [ -z "$EVENT_HUB_CONNECTION_STRING" ]; then
        log_warn "Could not fetch Event Hub connection string. Will use managed identity instead."
    else
        log_info "Event Hub connection string retrieved successfully"
    fi
}

# =============================================================================
# Configure kubectl context
# =============================================================================
configure_kubectl() {
    log_info "Configuring kubectl for AKS cluster: $AKS_CLUSTER_NAME"
    
    az aks get-credentials \
        --resource-group "$RESOURCE_GROUP" \
        --name "$AKS_CLUSTER_NAME" \
        --overwrite-existing
    
    # Create namespace if it doesn't exist
    kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
}

# =============================================================================
# Deploy test-api
# =============================================================================
deploy_test_api() {
    log_info "Deploying test-api..."
    
    helm upgrade --install test-api ./helm/test-api \
        --namespace "$NAMESPACE" \
        --set image.repository="${ACR_LOGIN_SERVER}/test-api" \
        --set image.tag="latest" \
        --wait
    
    log_info "test-api deployed successfully"
}

# =============================================================================
# Deploy test-consumer
# =============================================================================
deploy_test_consumer() {
    log_info "Deploying test-consumer..."
    
    # Prompt for SQL credentials if not set
    if [ -z "$SQL_USERNAME" ]; then
        read -p "Enter SQL Server username: " SQL_USERNAME
    fi
    if [ -z "$SQL_PASSWORD" ]; then
        read -sp "Enter SQL Server password: " SQL_PASSWORD
        echo
    fi
    
    helm upgrade --install test-consumer ./helm/test-consumer \
        --namespace "$NAMESPACE" \
        --set image.repository="${ACR_LOGIN_SERVER}/test-consumer" \
        --set image.tag="latest" \
        --set config.eventHub.namespace="$EVENT_HUB_NAMESPACE" \
        --set config.kafka.topic="$EVENT_HUB_NAME" \
        --set config.kafka.topicDlq="${EVENT_HUB_NAME}-dlq" \
        --set config.kafka.topicError="${EVENT_HUB_NAME}-error" \
        --set config.database.host="$SQL_SERVER_FQDN" \
        --set config.database.name="$SQL_DATABASE_NAME" \
        --set secrets.eventHubConnectionString="$EVENT_HUB_CONNECTION_STRING" \
        --set secrets.sqlUsername="$SQL_USERNAME" \
        --set secrets.sqlPassword="$SQL_PASSWORD" \
        --wait
    
    log_info "test-consumer deployed successfully"
}

# =============================================================================
# Deploy WireMock (optional, for testing)
# =============================================================================
deploy_wiremock() {
    log_info "Deploying WireMock..."
    
    helm upgrade --install wiremock ./helm/wiremock \
        --namespace "$NAMESPACE" \
        --wait
    
    log_info "WireMock deployed successfully"
}

# =============================================================================
# Generate values file from Bicep outputs
# =============================================================================
generate_values_file() {
    log_info "Generating Helm values file from Bicep outputs..."
    
    # Prompt for SQL credentials if not set
    if [ -z "$SQL_USERNAME" ]; then
        read -p "Enter SQL Server username: " SQL_USERNAME
    fi
    if [ -z "$SQL_PASSWORD" ]; then
        read -sp "Enter SQL Server password: " SQL_PASSWORD
        echo
    fi
    
    cat > ./helm/generated-values.yaml << EOF
# Auto-generated values from Bicep deployment
# Resource Group: $RESOURCE_GROUP
# Generated at: $(date -u +"%Y-%m-%dT%H:%M:%SZ")

# Test API values
test-api:
  image:
    repository: ${ACR_LOGIN_SERVER}/test-api
    tag: latest

# Test Consumer values
test-consumer:
  image:
    repository: ${ACR_LOGIN_SERVER}/test-consumer
    tag: latest
  
  config:
    eventHub:
      namespace: "${EVENT_HUB_NAMESPACE}"
    
    kafka:
      topic: "${EVENT_HUB_NAME}"
      topicDlq: "${EVENT_HUB_NAME}-dlq"
      topicError: "${EVENT_HUB_NAME}-error"
    
    database:
      host: "${SQL_SERVER_FQDN}"
      name: "${SQL_DATABASE_NAME}"
  
  secrets:
    eventHubConnectionString: "${EVENT_HUB_CONNECTION_STRING}"
    sqlUsername: "${SQL_USERNAME}"
    sqlPassword: "${SQL_PASSWORD}"

# Environment info
azure:
  acrLoginServer: "${ACR_LOGIN_SERVER}"
  aksClusterName: "${AKS_CLUSTER_NAME}"
  appConfigEndpoint: "${APP_CONFIG_ENDPOINT}"
  appGatewayFqdn: "${APP_GATEWAY_FQDN}"
  sqlConnectionString: "${SQL_CONNECTION_STRING}"
EOF
    
    log_info "Generated ./helm/generated-values.yaml"
    log_warn "This file contains sensitive data. Do not commit to version control!"
}

# =============================================================================
# Print deployment status
# =============================================================================
print_status() {
    log_info "Deployment Status:"
    echo "========================================"
    kubectl get pods -n "$NAMESPACE"
    echo "========================================"
    kubectl get services -n "$NAMESPACE"
    echo "========================================"
    log_info "Application Gateway FQDN: $APP_GATEWAY_FQDN"
}

# =============================================================================
# Usage
# =============================================================================
usage() {
    echo "Usage: $0 <command>"
    echo ""
    echo "Commands:"
    echo "  all              Deploy all components (test-api, test-consumer)"
    echo "  test-api         Deploy only test-api"
    echo "  test-consumer    Deploy only test-consumer"
    echo "  wiremock         Deploy WireMock for testing"
    echo "  generate-values  Generate Helm values file from Bicep outputs"
    echo "  status           Show deployment status"
    echo ""
    echo "Environment variables:"
    echo "  RESOURCE_GROUP   Azure resource group name (default: eventhub-poc-rg)"
    echo "  NAMESPACE        Kubernetes namespace (default: eventhub-poc)"
    echo "  NAME_PREFIX      Resource name prefix (default: myapp)"
    echo "  SQL_USERNAME     SQL Server username"
    echo "  SQL_PASSWORD     SQL Server password"
}

# =============================================================================
# Main
# =============================================================================
main() {
    if [ $# -lt 1 ]; then
        usage
        exit 1
    fi
    
    COMMAND=$1
    
    # Always fetch Bicep outputs first
    get_bicep_outputs
    get_eventhub_connection_string
    
    case $COMMAND in
        all)
            configure_kubectl
            deploy_test_api
            deploy_test_consumer
            print_status
            ;;
        test-api)
            configure_kubectl
            deploy_test_api
            print_status
            ;;
        test-consumer)
            configure_kubectl
            deploy_test_consumer
            print_status
            ;;
        wiremock)
            configure_kubectl
            deploy_wiremock
            print_status
            ;;
        generate-values)
            generate_values_file
            ;;
        status)
            configure_kubectl
            print_status
            ;;
        *)
            log_error "Unknown command: $COMMAND"
            usage
            exit 1
            ;;
    esac
}

main "$@"

