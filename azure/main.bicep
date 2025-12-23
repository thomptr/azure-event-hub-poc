@description('The Azure location for all resources.')
param location string = resourceGroup().location

@description('Name prefix for resources.')
param namePrefix string = 'myapp'

@description('Your home router public IP address for access restriction.')
param allowedIp string

@description('SSH public key for AKS Linux nodes.')
param sshRSAPublicKey string

@description('Admin username for AKS Linux nodes.')
param linuxAdminUsername string = 'azureuser'

@description('AKS node count.')
@minValue(1)
@maxValue(50)
param agentCount int = 3

@description('AKS VM size.')
param agentVMSize string = 'Standard_D2s_v3'

@description('Event Hub SKU (Basic or Standard).')
@allowed(['Basic', 'Standard'])
param eventHubSku string = 'Standard'

@description('Event Hub partition count.')
@minValue(1)
@maxValue(32)
param eventHubPartitionCount int = 2

@description('Event Hub message retention in days.')
@minValue(1)
@maxValue(7)
param eventHubRetentionDays int = 1

@description('SQL Server administrator login.')
param sqlAdminLogin string = 'sqladmin'

@secure()
@description('SQL Server administrator password.')
param sqlAdminPassword string

@description('Azure AD Object ID of the user to grant Grafana Admin access. Leave empty to skip.')
param grafanaAdminPrincipalId string = ''

// ============================================================================
// Variables
// ============================================================================

var vnetName = '${namePrefix}-vnet'
var aksSubnetName = 'aks-subnet'
var agSubnetName = 'appgw-subnet'
var vnetAddressPrefix = '10.0.0.0/8'
var aksSubnetPrefix = '10.0.0.0/16'
var agSubnetPrefix = '10.240.0.0/16'
var aksName = '${namePrefix}-aks'
var acrName = 'eventhubpocacr'
var eventHubNamespaceName = replace('${namePrefix}eventhubns', '-', '')
var eventHubName = '${namePrefix}-eventhub'
// Use uniqueString for globally unique names
var uniqueSuffix = uniqueString(resourceGroup().id)
var appConfigName = '${namePrefix}-appconfig-${uniqueSuffix}'
var sqlServerName = '${namePrefix}-sql-${uniqueSuffix}'
var sqlDatabaseName = '${namePrefix}-db'
var appGatewayName = '${namePrefix}-appgw'
var publicIpName = '${namePrefix}-appgw-pip'
var aksIdentityName = '${namePrefix}-aks-identity'
var appGatewayIdentityName = '${namePrefix}-appgw-identity'

// ============================================================================
// User Assigned Managed Identities
// ============================================================================

resource aksIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: aksIdentityName
  location: location
}

resource appGatewayIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: appGatewayIdentityName
  location: location
}

// ============================================================================
// Virtual Network
// ============================================================================

resource vnet 'Microsoft.Network/virtualNetworks@2023-09-01' = {
  name: vnetName
  location: location
  properties: {
    addressSpace: {
      addressPrefixes: [
        vnetAddressPrefix
      ]
    }
    subnets: [
      {
        name: aksSubnetName
        properties: {
          addressPrefix: aksSubnetPrefix
          privateEndpointNetworkPolicies: 'Disabled'
        }
      }
      {
        name: agSubnetName
        properties: {
          addressPrefix: agSubnetPrefix
        }
      }
    ]
  }
}

resource aksSubnet 'Microsoft.Network/virtualNetworks/subnets@2023-09-01' existing = {
  parent: vnet
  name: aksSubnetName
}

resource agSubnet 'Microsoft.Network/virtualNetworks/subnets@2023-09-01' existing = {
  parent: vnet
  name: agSubnetName
}

// ============================================================================
// Public IP for Application Gateway
// ============================================================================

resource publicIp 'Microsoft.Network/publicIPAddresses@2023-09-01' = {
  name: publicIpName
  location: location
  sku: {
    name: 'Standard'
    tier: 'Regional'
  }
  properties: {
    publicIPAllocationMethod: 'Static'
    publicIPAddressVersion: 'IPv4'
    dnsSettings: {
      domainNameLabel: '${namePrefix}-app'
    }
  }
}

// ============================================================================
// Application Gateway with WAF
// ============================================================================

resource applicationGateway 'Microsoft.Network/applicationGateways@2023-09-01' = {
  name: appGatewayName
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${appGatewayIdentity.id}': {}
    }
  }
  properties: {
    sku: {
      name: 'WAF_v2'
      tier: 'WAF_v2'
      capacity: 1
    }
    gatewayIPConfigurations: [
      {
        name: 'appGatewayIpConfig'
        properties: {
          subnet: {
            id: agSubnet.id
          }
        }
      }
    ]
    frontendIPConfigurations: [
      {
        name: 'appGwPublicFrontendIp'
        properties: {
          publicIPAddress: {
            id: publicIp.id
          }
        }
      }
    ]
    frontendPorts: [
      {
        name: 'port_80'
        properties: {
          port: 80
        }
      }
      {
        name: 'port_443'
        properties: {
          port: 443
        }
      }
    ]
    backendAddressPools: [
      {
        name: 'aksBackendPool'
        properties: {
          backendAddresses: []
        }
      }
    ]
    backendHttpSettingsCollection: [
      {
        name: 'aksBackendHttpSettings'
        properties: {
          port: 8080
          protocol: 'Http'
          cookieBasedAffinity: 'Disabled'
          pickHostNameFromBackendAddress: false
          requestTimeout: 30
          probe: {
            id: resourceId('Microsoft.Network/applicationGateways/probes', appGatewayName, 'aksHealthProbe')
          }
        }
      }
    ]
    httpListeners: [
      {
        name: 'httpListener'
        properties: {
          frontendIPConfiguration: {
            id: resourceId('Microsoft.Network/applicationGateways/frontendIPConfigurations', appGatewayName, 'appGwPublicFrontendIp')
          }
          frontendPort: {
            id: resourceId('Microsoft.Network/applicationGateways/frontendPorts', appGatewayName, 'port_80')
          }
          protocol: 'Http'
        }
      }
    ]
    requestRoutingRules: [
      {
        name: 'aksRoutingRule'
        properties: {
          priority: 100
          ruleType: 'Basic'
          httpListener: {
            id: resourceId('Microsoft.Network/applicationGateways/httpListeners', appGatewayName, 'httpListener')
          }
          backendAddressPool: {
            id: resourceId('Microsoft.Network/applicationGateways/backendAddressPools', appGatewayName, 'aksBackendPool')
          }
          backendHttpSettings: {
            id: resourceId('Microsoft.Network/applicationGateways/backendHttpSettingsCollection', appGatewayName, 'aksBackendHttpSettings')
          }
        }
      }
    ]
    probes: [
      {
        name: 'aksHealthProbe'
        properties: {
          protocol: 'Http'
          host: '127.0.0.1'
          path: '/healthz'
          interval: 30
          timeout: 30
          unhealthyThreshold: 3
          pickHostNameFromBackendHttpSettings: false
        }
      }
    ]
    webApplicationFirewallConfiguration: {
      enabled: true
      firewallMode: 'Prevention'
      ruleSetType: 'OWASP'
      ruleSetVersion: '3.2'
      requestBodyCheck: true
      maxRequestBodySizeInKb: 128
      fileUploadLimitInMb: 100
    }
  }
}

// ============================================================================
// Network Security Group for IP Restriction
// ============================================================================

resource appGatewayNsg 'Microsoft.Network/networkSecurityGroups@2023-09-01' = {
  name: '${appGatewayName}-nsg'
  location: location
  properties: {
    securityRules: [
      {
        name: 'AllowHomeRouterHTTPS'
        properties: {
          priority: 100
          direction: 'Inbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourcePortRange: '*'
          destinationPortRange: '443'
          sourceAddressPrefix: allowedIp
          destinationAddressPrefix: '*'
        }
      }
      {
        name: 'AllowHomeRouterHTTP'
        properties: {
          priority: 110
          direction: 'Inbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourcePortRange: '*'
          destinationPortRange: '80'
          sourceAddressPrefix: allowedIp
          destinationAddressPrefix: '*'
        }
      }
      {
        name: 'AllowGatewayManager'
        properties: {
          priority: 120
          direction: 'Inbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourcePortRange: '*'
          destinationPortRange: '65200-65535'
          sourceAddressPrefix: 'GatewayManager'
          destinationAddressPrefix: '*'
        }
      }
      {
        name: 'AllowAzureLoadBalancer'
        properties: {
          priority: 130
          direction: 'Inbound'
          access: 'Allow'
          protocol: '*'
          sourcePortRange: '*'
          destinationPortRange: '*'
          sourceAddressPrefix: 'AzureLoadBalancer'
          destinationAddressPrefix: '*'
        }
      }
      {
        name: 'DenyAllInbound'
        properties: {
          priority: 4096
          direction: 'Inbound'
          access: 'Deny'
          protocol: '*'
          sourcePortRange: '*'
          destinationPortRange: '*'
          sourceAddressPrefix: '*'
          destinationAddressPrefix: '*'
        }
      }
    ]
  }
}

// ============================================================================
// Azure Container Registry
// ============================================================================

resource acr 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: acrName
  location: location
  sku: {
    name: 'Basic'
  }
  properties: {
    adminUserEnabled: false
    publicNetworkAccess: 'Enabled'
    policies: {
      quarantinePolicy: {
        status: 'disabled'
      }
      trustPolicy: {
        type: 'Notary'
        status: 'disabled'
      }
      retentionPolicy: {
        days: 7
        status: 'disabled'
      }
    }
  }
}

// ============================================================================
// Azure Kubernetes Service
// ============================================================================

resource aks 'Microsoft.ContainerService/managedClusters@2024-01-01' = {
  name: aksName
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${aksIdentity.id}': {}
    }
  }
  properties: {
    dnsPrefix: '${namePrefix}-dns'
    kubernetesVersion: '1.32'
    enableRBAC: true
    agentPoolProfiles: [
      {
        name: 'agentpool'
        count: agentCount
        vmSize: agentVMSize
        osType: 'Linux'
        osSKU: 'Ubuntu'
        mode: 'System'
        vnetSubnetID: aksSubnet.id
        enableAutoScaling: false
        maxPods: 110
        type: 'VirtualMachineScaleSets'
      }
    ]
    linuxProfile: {
      adminUsername: linuxAdminUsername
      ssh: {
        publicKeys: [
          {
            keyData: sshRSAPublicKey
          }
        ]
      }
    }
    networkProfile: {
      networkPlugin: 'azure'
      networkPolicy: 'azure'
      serviceCidr: '10.100.0.0/16'
      dnsServiceIP: '10.100.0.10'
      loadBalancerSku: 'standard'
    }
    addonProfiles: {
      ingressApplicationGateway: {
        enabled: true
        config: {
          applicationGatewayId: applicationGateway.id
        }
      }
      omsagent: {
        enabled: true
        config: {
          logAnalyticsWorkspaceResourceID: logAnalyticsWorkspace.id
        }
      }
    }
    azureMonitorProfile: {
      metrics: {
        enabled: true
      }
    }
    oidcIssuerProfile: {
      enabled: true
    }
    securityProfile: {
      workloadIdentity: {
        enabled: true
      }
    }
  }
}

// ============================================================================
// Event Hub Namespace and Event Hub
// ============================================================================

resource eventHubNamespace 'Microsoft.EventHub/namespaces@2023-01-01-preview' = {
  name: eventHubNamespaceName
  location: location
  sku: {
    name: eventHubSku
    tier: eventHubSku
    capacity: 1
  }
  properties: {
    isAutoInflateEnabled: false
    kafkaEnabled: true
    publicNetworkAccess: 'Enabled'
    minimumTlsVersion: '1.2'
  }
}

resource eventHub 'Microsoft.EventHub/namespaces/eventhubs@2023-01-01-preview' = {
  parent: eventHubNamespace
  name: eventHubName
  properties: {
    partitionCount: eventHubPartitionCount
    messageRetentionInDays: eventHubRetentionDays
  }
}

resource eventHubConsumerGroup 'Microsoft.EventHub/namespaces/eventhubs/consumergroups@2023-01-01-preview' = {
  parent: eventHub
  name: '${namePrefix}-consumer-group'
  properties: {}
}

// ============================================================================
// Azure App Configuration
// ============================================================================

resource appConfig 'Microsoft.AppConfiguration/configurationStores@2023-03-01' = {
  name: appConfigName
  location: location
  sku: {
    name: 'free'
  }
  properties: {
    publicNetworkAccess: 'Enabled'
    disableLocalAuth: false
    enablePurgeProtection: false
  }
}

// ============================================================================
// Azure Monitor - Log Analytics Workspace & Application Insights
// ============================================================================

resource logAnalyticsWorkspace 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: '${namePrefix}-logs'
  location: location
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: 30
    features: {
      enableLogAccessUsingOnlyResourcePermissions: true
    }
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
  }
}

resource applicationInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: '${namePrefix}-appinsights'
  location: location
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: logAnalyticsWorkspace.id
    IngestionMode: 'LogAnalytics'
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
    RetentionInDays: 30
  }
}

// ============================================================================
// Azure Monitor Workspace (for Prometheus metrics)
// ============================================================================

resource azureMonitorWorkspace 'Microsoft.Monitor/accounts@2023-04-03' = {
  name: '${namePrefix}-prometheus'
  location: location
  properties: {}
}

// ============================================================================
// Azure Managed Grafana
// ============================================================================

resource grafana 'Microsoft.Dashboard/grafana@2023-09-01' = {
  name: '${namePrefix}-grafana'
  location: location
  sku: {
    name: 'Standard'
  }
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    publicNetworkAccess: 'Enabled'
    zoneRedundancy: 'Disabled'
    apiKey: 'Enabled'
    deterministicOutboundIP: 'Disabled'
    grafanaIntegrations: {
      azureMonitorWorkspaceIntegrations: [
        {
          azureMonitorWorkspaceResourceId: azureMonitorWorkspace.id
        }
      ]
    }
  }
}

// Grant Grafana access to read from Azure Monitor Workspace
var monitoringReaderRoleId = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '43d0d8ad-25c7-4714-9337-8ba259a9fe05')

resource grafanaMonitorReaderRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(azureMonitorWorkspace.id, grafana.id, monitoringReaderRoleId)
  scope: azureMonitorWorkspace
  properties: {
    principalId: grafana.identity.principalId
    roleDefinitionId: monitoringReaderRoleId
    principalType: 'ServicePrincipal'
  }
}

// Grant Grafana access to read from Log Analytics / Application Insights
resource grafanaLogAnalyticsReaderRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(logAnalyticsWorkspace.id, grafana.id, monitoringReaderRoleId)
  scope: logAnalyticsWorkspace
  properties: {
    principalId: grafana.identity.principalId
    roleDefinitionId: monitoringReaderRoleId
    principalType: 'ServicePrincipal'
  }
}

// Grant Grafana Admin role to a specified user (optional)
var grafanaAdminRoleId = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '22926164-76b3-42b3-bc55-97df8dab3e41')

resource grafanaAdminRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(grafanaAdminPrincipalId)) {
  name: guid(grafana.id, grafanaAdminPrincipalId, grafanaAdminRoleId)
  scope: grafana
  properties: {
    principalId: grafanaAdminPrincipalId
    roleDefinitionId: grafanaAdminRoleId
    principalType: 'User'
  }
}

// ============================================================================
// AKS Prometheus Data Collection Rule
// ============================================================================

resource dataCollectionEndpoint 'Microsoft.Insights/dataCollectionEndpoints@2022-06-01' = {
  name: '${namePrefix}-dce'
  location: location
  kind: 'Linux'
  properties: {
    networkAcls: {
      publicNetworkAccess: 'Enabled'
    }
  }
}

resource dataCollectionRule 'Microsoft.Insights/dataCollectionRules@2022-06-01' = {
  name: '${namePrefix}-prometheus-dcr'
  location: location
  kind: 'Linux'
  properties: {
    dataCollectionEndpointId: dataCollectionEndpoint.id
    dataSources: {
      prometheusForwarder: [
        {
          name: 'PrometheusDataSource'
          streams: ['Microsoft-PrometheusMetrics']
          labelIncludeFilter: {}
        }
      ]
    }
    destinations: {
      monitoringAccounts: [
        {
          name: 'MonitoringAccount'
          accountResourceId: azureMonitorWorkspace.id
        }
      ]
    }
    dataFlows: [
      {
        streams: ['Microsoft-PrometheusMetrics']
        destinations: ['MonitoringAccount']
      }
    ]
  }
}

resource dataCollectionRuleAssociation 'Microsoft.Insights/dataCollectionRuleAssociations@2022-06-01' = {
  name: '${namePrefix}-dcra'
  scope: aks
  properties: {
    dataCollectionRuleId: dataCollectionRule.id
    description: 'Association of data collection rule for AKS Prometheus metrics'
  }
}

// ============================================================================
// Azure SQL Server and Database (Basic tier - lowest cost)
// ============================================================================

resource sqlServer 'Microsoft.Sql/servers@2023-05-01-preview' = {
  name: sqlServerName
  location: location
  properties: {
    administratorLogin: sqlAdminLogin
    administratorLoginPassword: sqlAdminPassword
    version: '12.0'
    minimalTlsVersion: '1.2'
    publicNetworkAccess: 'Enabled'
  }
}

resource sqlDatabase 'Microsoft.Sql/servers/databases@2023-05-01-preview' = {
  parent: sqlServer
  name: sqlDatabaseName
  location: location
  sku: {
    name: 'Basic'
    tier: 'Basic'
    capacity: 5
  }
  properties: {
    collation: 'SQL_Latin1_General_CP1_CI_AS'
    maxSizeBytes: 2147483648 // 2GB
    catalogCollation: 'SQL_Latin1_General_CP1_CI_AS'
    zoneRedundant: false
    readScale: 'Disabled'
    requestedBackupStorageRedundancy: 'Local'
  }
}

// Allow Azure services to access SQL Server
resource sqlFirewallAllowAzure 'Microsoft.Sql/servers/firewallRules@2023-05-01-preview' = {
  parent: sqlServer
  name: 'AllowAllAzureIps'
  properties: {
    startIpAddress: '0.0.0.0'
    endIpAddress: '0.0.0.0'
  }
}

// Allow home IP to access SQL Server for debugging
resource sqlFirewallAllowHome 'Microsoft.Sql/servers/firewallRules@2023-05-01-preview' = {
  parent: sqlServer
  name: 'AllowHomeIp'
  properties: {
    startIpAddress: allowedIp
    endIpAddress: allowedIp
  }
}

// Set Azure AD admin for SQL Server (allows portal query editor access)
resource sqlAadAdmin 'Microsoft.Sql/servers/administrators@2023-05-01-preview' = if (!empty(grafanaAdminPrincipalId)) {
  parent: sqlServer
  name: 'ActiveDirectory'
  properties: {
    administratorType: 'ActiveDirectory'
    login: 'AzureAD Admin'
    sid: grafanaAdminPrincipalId
    tenantId: subscription().tenantId
  }
}

// ============================================================================
// Role Assignments
// ============================================================================

// AKS identity needs AcrPull role on ACR
var acrPullRoleDefinitionId = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '7f951dda-4ed3-4680-a7ca-43fe172d538d')

resource acrPullRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(acr.id, aks.id, acrPullRoleDefinitionId)
  scope: acr
  properties: {
    principalId: aks.properties.identityProfile.kubeletidentity.objectId
    roleDefinitionId: acrPullRoleDefinitionId
    principalType: 'ServicePrincipal'
  }
}

// AKS identity needs Network Contributor on VNet for AGIC
var networkContributorRoleDefinitionId = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '4d97b98b-1d4f-4787-a291-c67834d212e7')

resource vnetRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(vnet.id, aks.id, networkContributorRoleDefinitionId)
  scope: vnet
  properties: {
    principalId: aksIdentity.properties.principalId
    roleDefinitionId: networkContributorRoleDefinitionId
    principalType: 'ServicePrincipal'
  }
}

// AKS AGIC needs Contributor on Application Gateway
var contributorRoleDefinitionId = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'b24988ac-6180-42a0-ab88-20f7382dd24c')

resource appGwContributorRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(applicationGateway.id, aks.id, contributorRoleDefinitionId)
  scope: applicationGateway
  properties: {
    principalId: aks.properties.addonProfiles.ingressApplicationGateway.identity.objectId
    roleDefinitionId: contributorRoleDefinitionId
    principalType: 'ServicePrincipal'
  }
}

// AKS identity needs Event Hub Data Receiver role
var eventHubDataReceiverRoleId = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'a638d3c7-ab3a-418d-83e6-5f17a39d4fde')

resource eventHubReceiverRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(eventHubNamespace.id, aks.id, eventHubDataReceiverRoleId)
  scope: eventHubNamespace
  properties: {
    principalId: aks.properties.identityProfile.kubeletidentity.objectId
    roleDefinitionId: eventHubDataReceiverRoleId
    principalType: 'ServicePrincipal'
  }
}

// AKS identity needs App Configuration Data Reader role
var appConfigDataReaderRoleId = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '516239f1-63e1-4d78-a4de-a74fb236a071')

resource appConfigReaderRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(appConfig.id, aks.id, appConfigDataReaderRoleId)
  scope: appConfig
  properties: {
    principalId: aks.properties.identityProfile.kubeletidentity.objectId
    roleDefinitionId: appConfigDataReaderRoleId
    principalType: 'ServicePrincipal'
  }
}

// ============================================================================
// Outputs
// ============================================================================

output aksClusterName string = aks.name
output aksClusterFqdn string = aks.properties.fqdn
output acrLoginServer string = acr.properties.loginServer
output eventHubNamespaceName string = eventHubNamespace.name
output eventHubName string = eventHub.name
output appConfigEndpoint string = appConfig.properties.endpoint
output applicationGatewayPublicIp string = publicIp.properties.ipAddress
output applicationGatewayFqdn string = publicIp.properties.dnsSettings.fqdn
output vnetName string = vnet.name
output aksSubnetId string = aksSubnet.id
output allowedIpAddress string = allowedIp
output sqlServerFqdn string = sqlServer.properties.fullyQualifiedDomainName
output sqlDatabaseName string = sqlDatabase.name
output sqlConnectionString string = 'jdbc:sqlserver://${sqlServer.properties.fullyQualifiedDomainName}:1433;database=${sqlDatabase.name};encrypt=true;trustServerCertificate=false;hostNameInCertificate=*.database.windows.net;loginTimeout=30;'
output applicationInsightsConnectionString string = applicationInsights.properties.ConnectionString
output applicationInsightsInstrumentationKey string = applicationInsights.properties.InstrumentationKey
output logAnalyticsWorkspaceId string = logAnalyticsWorkspace.id
output grafanaEndpoint string = grafana.properties.endpoint
output azureMonitorWorkspaceId string = azureMonitorWorkspace.id
