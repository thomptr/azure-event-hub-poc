# Test Producer

A high-performance Kafka message producer for testing the Azure Event Hub POC.

## Features

- Configurable message rate (messages per second)
- REST API to start/stop the producer
- Real-time statistics and metrics
- Prometheus metrics endpoint
- Random AccountMessage generation

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `producer.messages-per-second` | 100 | Target messages per second |
| `producer.topic` | myapp-eventhub | Kafka topic to send messages to |
| `producer.auto-start` | false | Start producing on application startup |

## REST API Endpoints

### Start Producer
```bash
curl -X POST http://localhost:8087/api/producer/start
```

### Stop Producer
```bash
curl -X POST http://localhost:8087/api/producer/stop
```

### Get Status
```bash
curl http://localhost:8087/api/producer/status
```

Response:
```json
{
  "running": true,
  "messagesPerSecond": 100,
  "messagesSent": 1000,
  "messagesSuccessful": 998,
  "messagesFailed": 2,
  "topic": "myapp-eventhub"
}
```

### Change Rate
```bash
curl -X POST "http://localhost:8087/api/producer/rate?messagesPerSecond=500"
```

### Reset Statistics
```bash
curl -X POST http://localhost:8087/api/producer/reset-stats
```

## Running Locally

### With Docker Compose (using local Kafka)

```bash
cd apps/test-producer
docker-compose up -d
```

### With Maven

```bash
cd apps/test-producer
mvn spring-boot:run
```

### With Azure Event Hub

```bash
cd apps/test-producer
mvn spring-boot:run -Dspring.profiles.active=azure \
  -DEVENTHUB_NAMESPACE=myappeventhubns.servicebus.windows.net:9093 \
  -DEVENTHUB_CONNECTION_STRING="Endpoint=sb://..."
```

## Building Docker Image

```bash
cd apps/test-producer
docker build -t test-producer:v1 .

# Push to ACR
az acr login --name eventhubpocacr
docker tag test-producer:v1 eventhubpocacr.azurecr.io/test-producer:v1
docker push eventhubpocacr.azurecr.io/test-producer:v1
```

## Metrics

Prometheus metrics available at `/actuator/prometheus`:

- `producer.messages.sent` - Total messages sent
- `producer.messages.success` - Successful messages
- `producer.messages.failed` - Failed messages

## Message Format

The producer generates random AccountMessage objects:

```json
{
  "firstName": "John",
  "lastName": "Smith",
  "accountNumber": "ACC-1A2B3C4D",
  "accountAction": "CREATE"
}
```

### Account Actions

Randomly selected from:
- CREATE, UPDATE, DELETE
- ACTIVATE, DEACTIVATE, SUSPEND, REACTIVATE
- VERIFY, CLOSE, TRANSFER
- UPGRADE, DOWNGRADE

