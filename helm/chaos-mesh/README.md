# Chaos Mesh Setup

Chaos Mesh is a cloud-native Chaos Engineering platform that orchestrates chaos experiments on Kubernetes environments.

Reference: [Chaos Mesh Installation Guide](https://chaos-mesh.org/docs/production-installation-using-helm/)

## Prerequisites

- Kubernetes cluster (AKS)
- Helm 3.x installed
- `kubectl` configured to connect to your cluster

## Installation

### 1. Add Chaos Mesh Helm Repository

```bash
helm repo add chaos-mesh https://charts.chaos-mesh.org
helm repo update
```

### 2. View Available Versions

```bash
# Latest version
helm search repo chaos-mesh

# All versions
helm search repo chaos-mesh -l
```

### 3. Create Namespace

```bash
kubectl create namespace chaos-mesh
```

### 4. Install Chaos Mesh

For AKS with containerd runtime:

```bash
helm install chaos-mesh chaos-mesh/chaos-mesh \
  --namespace chaos-mesh \
  --version 2.8.0 \
  --values values.yaml
```

### 5. Verify Installation

```bash
kubectl get pods -n chaos-mesh
```

Expected output:
```
NAME                                       READY   STATUS    RESTARTS   AGE
chaos-controller-manager-xxx               1/1     Running   0          2m
chaos-daemon-xxx                           1/1     Running   0          2m
chaos-dashboard-xxx                        1/1     Running   0          2m
chaos-dns-server-xxx                       1/1     Running   0          2m
```

## Access Chaos Dashboard

### Port Forward

```bash
kubectl port-forward svc/chaos-dashboard 2333:2333 -n chaos-mesh
```

Then open: http://localhost:2333

### Via Ingress (Optional)

Enable ingress in `values.yaml` and apply.

## Running Chaos Experiments

Apply the example experiments in the `experiments/` directory:

```bash
# Pod failure experiment
kubectl apply -f experiments/pod-failure.yaml

# Network delay experiment
kubectl apply -f experiments/network-delay.yaml

# HTTP fault injection
kubectl apply -f experiments/http-fault.yaml
```

## Upgrade Chaos Mesh

```bash
helm upgrade chaos-mesh chaos-mesh/chaos-mesh \
  --namespace chaos-mesh \
  --values values.yaml
```

## Uninstall

```bash
helm uninstall chaos-mesh -n chaos-mesh
kubectl delete namespace chaos-mesh
```

## Experiment Types

| Type | Description |
|------|-------------|
| PodChaos | Kill pods, container failures |
| NetworkChaos | Network delay, loss, corruption |
| HTTPChaos | HTTP request/response faults |
| StressChaos | CPU/memory stress |
| IOChaos | File system I/O faults |
| DNSChaos | DNS resolution faults |
| TimeChaos | Time skew |

