# Test Consumer

A Spring Boot Kafka consumer application for consuming account messages from Event Hub.

## Features

- Spring Kafka consumer for processing account messages
- **WebClient integration** to call test-api after consuming messages
- **SQL Server database** persistence for successful API responses
- **Blocking retries** for network errors (WebClientRequestException)
- **Dead Letter Queue (DLQ)** for messages that fail after exhausting retries
- **Error Topic** for non-retryable errors (4xx/5xx HTTP responses)
- Spring Boot Actuator for health checks
- Micrometer metrics with Dynatrace registry
- OpenTelemetry for distributed tracing
- Azure Event Hub compatible (Kafka protocol)

## Message Flow

```
┌─────────────┐     ┌──────────────┐     ┌──────────────┐
│   Kafka     │────▶│   Consumer   │────▶│   test-api   │
│  (accounts) │     │  (3 retries) │     │  POST /api/  │
└─────────────┘     └──────┬───────┘     └──────┬───────┘
                          │                     │
                          │              ┌──────▼───────┐
                          │              │   Success    │
                          │              │   Response   │
                          │              └──────┬───────┘
                          │                     │
            ┌─────────────┼─────────────┐       │
            │             │             │       ▼
            ▼             ▼             ▼        _____________
       ┌─────────┐  ┌─────────┐  ┌─────────────┐|             |
       │ Success │  │   DLQ   │  │ Error Topic ││  SQL Server │
       │  (log)  │  │(retries │  │ (4xx/5xx)   ││  Database   │
       └────┬────┘  │exhausted│  └─────────────┘└─────────────┘
            │       └─────────┘
            │
            ▼
    ┌───────────────┐
    │  SQL Server   │
    │   Database    │
    │ (transactions)│
    └───────────────┘
```

**Flow Summary:**
1. Consumer receives message from Kafka
2. Calls test-api with retry logic (up to 3 attempts)
3. On success: saves response to SQL Server database
4. On retryable failure: sends to DLQ after exhausting retries
5. On non-retryable failure: sends to Error Topic

## Error Handling Strategy

| Error Type | Behavior | Destination |
|------------|----------|-------------|
| Network errors (timeout, connection refused) | Retry up to 3 times with exponential backoff | DLQ after retries exhausted |
| HTTP 5xx (server errors) | Retry up to 3 times | DLQ after retries exhausted |
| HTTP 429 (rate limited) | Retry up to 3 times | DLQ after retries exhausted |
| HTTP 4xx (client errors) | No retry | Error Topic |
| Other exceptions | No retry | Error Topic |

## Message Format

The consumer expects messages with this JSON structure:

```json
{
  "firstName": "John",
  "lastName": "Doe",
  "accountNumber": "1234567890",
  "accountAction": "CREATE"
}
```

Supported `accountAction` values: `CREATE`, `UPDATE`, `DELETE`

## Kafka Topics

| Topic | Purpose |
|-------|---------|
| `accounts` | Main topic for account messages |
| `accounts-dlq` | Dead Letter Queue for retryable errors |
| `accounts-error` | Error topic for non-retryable errors |

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/actuator/health` | Health check |
| GET | `/actuator/health/liveness` | Kubernetes liveness probe |
| GET | `/actuator/health/readiness` | Kubernetes readiness probe |
| GET | `/actuator/metrics` | Metrics endpoint |
| GET | `/actuator/prometheus` | Prometheus metrics |

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker addresses | `localhost:9092` |
| `KAFKA_CONSUMER_GROUP` | Consumer group ID | `test-consumer-group` |
| `KAFKA_TOPIC` | Topic to consume from | `accounts` |
| `KAFKA_TOPIC_DLQ` | Dead letter queue topic | `accounts-dlq` |
| `KAFKA_TOPIC_ERROR` | Error topic | `accounts-error` |
| `TEST_API_URL` | Base URL for test-api | `http://localhost:8089` |
| `TEST_API_RETRY_MAX` | Max retry attempts | `3` |
| `TEST_API_RETRY_DELAY` | Initial retry delay (ms) | `1000` |
| `DYNATRACE_ENABLED` | Enable Dynatrace metrics | `false` |
| `DYNATRACE_URI` | Dynatrace ingest endpoint | - |
| `DYNATRACE_API_TOKEN` | Dynatrace API token | - |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OpenTelemetry collector endpoint | `http://localhost:4318` |

### Database Variables (Local - H2)

| Variable | Description | Default |
|----------|-------------|---------|
| `DATABASE_URL` | JDBC connection URL | `jdbc:h2:mem:testdb` |
| `DATABASE_USERNAME` | Database username | `sa` |
| `DATABASE_PASSWORD` | Database password | (empty) |
| `H2_CONSOLE_ENABLED` | Enable H2 web console | `true` |

### Database Variables (Azure SQL Server)

| Variable | Description |
|----------|-------------|
| `SQL_SERVER_HOST` | SQL Server FQDN (e.g., `myapp-sqlserver.database.windows.net`) |
| `SQL_DATABASE_NAME` | Database name |
| `SQL_USERNAME` | SQL Server admin username |
| `SQL_PASSWORD` | SQL Server admin password |

### Azure Event Hub Variables

| Variable | Description |
|----------|-------------|
| `EVENTHUB_NAMESPACE` | Event Hub namespace (e.g., `myappns`) |
| `EVENTHUB_NAME` | Event Hub name |
| `EVENTHUB_CONSUMER_GROUP` | Consumer group |
| `EVENTHUB_CONNECTION_STRING` | Event Hub connection string |

## Run Locally (with local Kafka)

```bash
# Start test-api first
cd ../test-api
docker compose up -d

# Run the consumer
cd ../test-consumer
./mvnw spring-boot:run
```

## Run with Azure Event Hub and SQL Server

```bash
# Event Hub settings
export EVENTHUB_NAMESPACE=myappeventhubns
export EVENTHUB_NAME=myapp-eventhub
export EVENTHUB_CONSUMER_GROUP=test-consumer-group
export EVENTHUB_CONNECTION_STRING="Endpoint=sb://..."

# SQL Server settings
export SQL_SERVER_HOST=myapp-sqlserver.database.windows.net
export SQL_DATABASE_NAME=myapp-db
export SQL_USERNAME=sqladmin
export SQL_PASSWORD=your-password

# API settings
export TEST_API_URL=http://test-api:8089

./mvnw spring-boot:run -Dspring-boot.run.profiles=azure
```

## Run with Docker

```bash
# Build
docker build -t test-consumer:latest .

# Run with local Kafka and test-api
docker run -p 8088:8088 \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  -e TEST_API_URL=http://host.docker.internal:8089 \
  test-consumer:latest

# Run with Azure Event Hub
docker run -p 8088:8088 \
  -e SPRING_PROFILES_ACTIVE=azure \
  -e EVENTHUB_NAMESPACE=myappeventhubns \
  -e EVENTHUB_NAME=myapp-eventhub \
  -e EVENTHUB_CONNECTION_STRING="Endpoint=sb://..." \
  -e TEST_API_URL=http://test-api:8089 \
  test-consumer:latest
```

## Metrics

Custom metrics exposed:

| Metric | Description |
|--------|-------------|
| `kafka.messages.consumed` | Count of messages consumed from Kafka |
| `kafka.messages.success` | Count of messages processed successfully |
| `kafka.messages.errors` | Count of message processing errors |
| `kafka.messages.dlq` | Count of messages sent to DLQ |
| `kafka.messages.error` | Count of messages sent to error topic |
| `database.save.success` | Count of successful database saves |
| `database.save.errors` | Count of database save errors |

## Dynatrace Integration

To enable Dynatrace metrics:

```bash
export DYNATRACE_ENABLED=true
export DYNATRACE_URI=https://{your-environment-id}.live.dynatrace.com/api/v2/metrics/ingest
export DYNATRACE_API_TOKEN=your-api-token
```

## OpenTelemetry Integration

Configure the OTLP endpoint for your collector:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
```

## Push to ACR

```bash
az acr login --name eventhubpocacr
docker tag test-consumer:latest eventhubpocacr.azurecr.io/test-consumer:v1
docker push eventhubpocacr.azurecr.io/test-consumer:v1
```
