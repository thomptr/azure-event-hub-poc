# Test API

A simple Spring Boot REST API for testing purposes.

## Features

- REST POST endpoint that returns randomly generated account data
- Spring Boot Actuator for health checks (Kubernetes ready)

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/accounts` | Echoes request with random message |
| GET | `/actuator/health` | Health check |
| GET | `/actuator/health/liveness` | Kubernetes liveness probe |
| GET | `/actuator/health/readiness` | Kubernetes readiness probe |

## Run Locally

```bash
# Using Maven
./mvnw spring-boot:run

# Or with Maven installed
mvn spring-boot:run
```

## Test the API

```bash
# Create account (echoes input, returns random message)
curl -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -d '{"firstName": "John", "lastName": "Doe", "accountNumber": "1234567890", "accountAction": "CREATE"}'

# Health check
curl http://localhost:8080/actuator/health
```

## Example Response

```json
{
  "firstName": "John",
  "lastName": "Doe",
  "accountNumber": "1234567890",
  "accountAction": "CREATE",
  "message": "Request processed successfully"
}
```

## Build Docker Image

```bash
docker build -t test-api:latest .
docker run -p 8080:8080 test-api:latest
```

## Run with Docker Compose (includes WireMock)

Docker Compose runs both the test-api and WireMock. WireMock acts as a proxy to capture all requests/responses.

```bash
# Start both services
docker compose up --build

# Or run in detached mode
docker compose up --build -d

# View logs
docker compose logs -f

# Stop services
docker compose down
```

### Ports

| Service | Port | Description |
|---------|------|-------------|
| test-api | 8080 | Direct access to the API |
| wiremock | 8081 | WireMock proxy (records requests to test-api) |

### Using WireMock to Record

Send requests through WireMock (port 8081) to record them:

```bash
# This request goes through WireMock and gets recorded
curl -X POST http://localhost:8081/api/accounts \
  -H "Content-Type: application/json" \
  -d '{"firstName": "John", "lastName": "Doe", "accountNumber": "1234567890", "accountAction": "CREATE"}'
```

Recorded mappings are saved to `wiremock/mappings/`.

### WireMock Admin API

```bash
# List all recorded mappings
curl http://localhost:8081/__admin/mappings

# Get WireMock status
curl http://localhost:8081/__admin/

# Save current mappings to files
curl -X POST http://localhost:8081/__admin/mappings/save

# Reset all mappings
curl -X POST http://localhost:8081/__admin/reset
```

### WireMock Directory Structure

```
wiremock/
├── mappings/     # Stub definitions (request/response mappings)
├── __files/      # Response body files
└── recordings/   # Recorded traffic
```

## Push to ACR

```bash
az acr login --name eventhubpocacr
docker tag test-api:latest eventhubpocacr.azurecr.io/test-api:v1
docker push eventhubpocacr.azurecr.io/test-api:v1
```

