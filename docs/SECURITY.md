# Security Guide

This document outlines how to handle sensitive information securely when working with this repository.

## Files That Should NEVER Be Committed

The following files contain sensitive information and are gitignored:

| File | Contains |
|------|----------|
| `azure/parameters.json` | IP address, SSH keys, SQL password |
| `.env` | All environment secrets |
| `helm/generated-values.yaml` | Event Hub connection string, SQL credentials |
| `k8s/secrets/*.yaml` | Kubernetes secrets |

## Sensitive Information Types

### 1. Network/Identity
- **Your public IP address** (`allowedIp`) - Used for firewall rules
- **SSH public key** - Used for AKS node access

### 2. Credentials
- **SQL Server password** - Database authentication
- **Event Hub connection string** - Message broker authentication
- **Dynatrace API token** - Monitoring authentication

## How to Handle Secrets

### Option 1: Example Files (Recommended for Development)

Use the provided example files and create your own local copies:

```bash
# Azure Bicep parameters
cp azure/parameters.json_example azure/parameters.json
# Edit azure/parameters.json with your values

# Environment variables
cp docs/env-example.txt .env
# Edit .env with your values

# Kubernetes secrets
cp k8s/secrets-example.yaml k8s/secrets/my-secrets.yaml
# Edit k8s/secrets/my-secrets.yaml with your values
```

### Option 2: Command-Line Parameters

Pass secrets via command line (they won't be stored in files):

```bash
# Bicep deployment
az deployment group create \
  --resource-group eventhub-poc-rg \
  --template-file azure/main.bicep \
  --parameters @azure/parameters.json_example \
  --parameters sqlAdminPassword='YourSecurePassword123!' \
  --parameters allowedIp='your.ip.address' \
  --parameters sshRSAPublicKey="$(cat ~/.ssh/id_rsa.pub)"

# Helm deployment
helm install test-consumer ./helm/test-consumer \
  --set secrets.sqlPassword='YourSecurePassword123!' \
  --set secrets.eventHubConnectionString='Endpoint=sb://...'
```

### Option 3: Environment Variables

```bash
# Load from .env file
source .env

# Use in commands
az deployment group create \
  --resource-group $RESOURCE_GROUP \
  --template-file azure/main.bicep \
  --parameters sqlAdminPassword="$SQL_ADMIN_PASSWORD"
```

### Option 4: Azure Key Vault (Production Recommended)

For production deployments, use Azure Key Vault:

```bash
# Create Key Vault
az keyvault create \
  --name myapp-keyvault \
  --resource-group eventhub-poc-rg \
  --location eastus

# Store secrets
az keyvault secret set \
  --vault-name myapp-keyvault \
  --name sql-admin-password \
  --value 'YourSecurePassword123!'

# Reference in Bicep
# Use: getSecret(subscription().subscriptionId, 'eventhub-poc-rg', 'myapp-keyvault', 'sql-admin-password')
```

## Kubernetes Secrets

### Creating Secrets Manually

```bash
# Create namespace first
kubectl create namespace eventhub-poc

# Create secret from literals
kubectl create secret generic test-consumer-secrets \
  --namespace eventhub-poc-consumer \
  --from-literal=event-hub-connection-string='Endpoint=sb://...' \
  --from-literal=sql-username='sqladmin' \
  --from-literal=sql-password='YourSecurePassword123!'

# Deploy Helm chart (uses existing secret)
helm install test-consumer ./helm/test-consumer \
  --namespace eventhub-poc-consumer \
  --set secrets.useExisting=true
```

### Creating Secrets via Helm

If you pass secrets to Helm, it will create the Kubernetes secret:

```bash
helm install test-consumer ./helm/test-consumer \
  --namespace eventhub-poc-consumer \
  --set secrets.eventHubConnectionString='Endpoint=sb://...' \
  --set secrets.sqlUsername='sqladmin' \
  --set secrets.sqlPassword='YourSecurePassword123!'
```

## Before Committing

Always verify no secrets will be committed:

```bash
# Check what files will be committed
git status

# Verify gitignore is working
git check-ignore -v azure/parameters.json
# Should output: .gitignore:XX:azure/parameters.json

# Search for potential secrets in staged files
git diff --cached --name-only | xargs grep -l -i "password\|secret\|key\|token" 2>/dev/null
```

## If You Accidentally Commit Secrets

If secrets were accidentally committed:

1. **Immediately rotate the compromised credentials**
2. Remove from Git history:
   ```bash
   # Using git-filter-repo (recommended)
   git filter-repo --path azure/parameters.json --invert-paths
   
   # Or using BFG Repo-Cleaner
   bfg --delete-files parameters.json
   ```
3. Force push to remote (if already pushed)
4. Notify team members to re-clone

## Security Checklist

Before making the repository public:

- [ ] `azure/parameters.json` is gitignored and not in history
- [ ] `azure/parameters.json_example` contains only placeholder values
- [ ] No IP addresses in committed files (except `xxx.xxx.xxx.xxx` placeholders)
- [ ] No passwords in committed files
- [ ] No SSH keys in committed files
- [ ] No connection strings in committed files
- [ ] `.gitignore` includes all secret file patterns
- [ ] Architecture diagrams use masked IPs (`xxx.xxx.xxx.xxx`)

## Verifying Repository is Clean

Run this before pushing to public:

```bash
# Search for potential secrets
grep -r -i "password" --include="*.json" --include="*.yaml" --include="*.yml" . | grep -v "REPLACE\|example\|placeholder"
grep -r -E "([0-9]{1,3}\.){3}[0-9]{1,3}" --include="*.json" --include="*.yaml" . | grep -v "0.0.0.0\|127.0.0.1\|10.\|xxx"
grep -r "ssh-rsa" --include="*.json" --include="*.yaml" . 
grep -r "Endpoint=sb://" --include="*.json" --include="*.yaml" .
```

All commands above should return empty results for a clean repository.

