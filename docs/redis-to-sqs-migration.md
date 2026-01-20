# Redis Streams to AWS SQS Migration Plan

## Overview

Replace all 6 Redis Streams with AWS SQS Standard Queues to gain:
- **Operational simplicity** - Fully managed, no Redis monitoring
- **Native DLQ support** - Automatic dead-letter queue routing after configurable retries
- **Cost efficiency** - ~$2-4/month for 100K messages/day

## Architecture Decision

### SQS Standard Queues (not FIFO)

| Factor | Decision |
|--------|----------|
| Queue type | Standard (not FIFO) |
| Throughput | Unlimited TPS |
| Ordering | Best-effort (acceptable - listeners are idempotent) |
| Delivery | At-least-once |

### AWS SDK v2 (not Spring Cloud AWS)

- Already using SDK v2 for S3
- Better control over trace context propagation
- Fewer dependencies

### Provider Abstraction Layer

Both Redis and SQS share common interfaces, allowing instant switching via configuration:

```
┌─────────────────────────────────────────────────────────────┐
│                      MessageHandler                         │
│              (auto-discovered via injection)                │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
┌──────────────────────────┐    ┌──────────────────────────┐
│  MessageConsumerManager  │    │    MessagePublisher      │
│      (interface)         │    │      (interface)         │
└──────────────────────────┘    └──────────────────────────┘
              │                               │
    ┌─────────┴─────────┐           ┌────────┴────────┐
    ▼                   ▼           ▼                 ▼
┌────────┐        ┌─────────┐  ┌────────┐       ┌─────────┐
│ Redis  │        │   SQS   │  │ Redis  │       │   SQS   │
│Consumer│        │Consumer │  │Publisher│      │Publisher│
└────────┘        └─────────┘  └────────┘       └─────────┘
    │                   │           │                 │
    └─────────┬─────────┘           └────────┬────────┘
              ▼                               ▼
     @ConditionalOnProperty          @ConditionalOnProperty
     messaging.provider=redis/sqs    messaging.provider=redis/sqs
```

## Queue Configuration

| Queue | Visibility Timeout | Max Retries | Batch Size | Use Case |
|-------|-------------------|-------------|------------|----------|
| email-local-uploads | 5 min | 3 | 10 | Email attachment S3 upload |
| ai-process | 10 min | 3 | 10 | Document AI extraction |
| vector-store-ingestion | 1 min | 5 | 10 | Vector embedding storage |
| document-approved-events | 2 min | 3 | 10 | Approval notifications |
| quickbooks-events | 5 min | 5 | 10 | QuickBooks webhook sync |
| integration-push-events | 10 min | 5 | 10 | Push sync to QuickBooks |

**Single consumer per queue** - Batch fetching (10 messages/request) provides sufficient throughput. Each queue has a dedicated DLQ with 14-day message retention.

## Retry Mechanism

SQS uses count-based retries:

```
Message received → Processing fails → Message becomes visible after visibilityTimeout
                                    → ApproximateReceiveCount increments
                                    → Repeats until maxReceiveCount exceeded
                                    → Message moves to DLQ
```

## Cost Estimate

For 100,000 messages/day (~3M/month):

| Scenario | Monthly Cost |
|----------|--------------|
| No batching | ~$3.20 |
| With batching | ~$0-2 |
| Including retries | ~$2-4 |

First 1 million requests/month are free.

## Implementation Steps

### Step 1: Add SQS Dependency

**gradle/libs.versions.toml:**
```toml
aws-sqs = { module = "software.amazon.awssdk:sqs", version.ref = "aws-sdk" }
```

**build.gradle:**
```gradle
implementation libs.aws.sqs
```

### Step 2: Create SQS Infrastructure

#### SqsProperties.java
```java
@ConfigurationProperties(prefix = "sqs")
@Data
public class SqsProperties {
    private String region = "us-east-1";
    private String endpoint;  // Empty for AWS, set for LocalStack
    private String queuePrefix = "tosspaper";
    private Map<String, QueueConfig> queues = new HashMap<>();

    @Data
    public static class QueueConfig {
        private int visibilityTimeoutSeconds = 30;
        private int maxReceiveCount = 3;        // Retries before DLQ
        private int pollDelaySeconds = 20;      // Long poll wait time
        private int maxMessages = 10;           // Messages per poll (1-10)
        private boolean enabled = true;
    }
}
```

#### SqsConfig.java
```java
@Configuration
@EnableConfigurationProperties(SqsProperties.class)
public class SqsConfig {

    @Bean
    public SqsClient sqsClient(SqsProperties properties, AwsProperties awsProperties) {
        var builder = SqsClient.builder()
            .region(Region.of(properties.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    awsProperties.getCredentials().getAccessKey(),
                    awsProperties.getCredentials().getSecretKey()
                )
            ));

        if (StringUtils.hasText(properties.getEndpoint())) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }

        return builder.build();
    }
}
```

#### SqsMessagePublisherImpl.java
```java
@Service
@ConditionalOnProperty(name = "messaging.provider", havingValue = "sqs")
@RequiredArgsConstructor
public class SqsMessagePublisherImpl implements MessagePublisher {

    private final SqsClient sqsClient;
    private final SqsProperties properties;
    private final Tracer tracer;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(String queueName, Map<String, String> message) {
        String queueUrl = getQueueUrl(queueName);

        // Build message attributes for trace context
        Map<String, MessageAttributeValue> attributes = new HashMap<>();

        // W3C traceparent
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            String traceparent = String.format("00-%s-%s-01",
                currentSpan.context().traceId(),
                currentSpan.context().spanId());
            attributes.put("traceparent", MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(traceparent)
                .build());
        }

        // Baggage propagation
        Baggage.current().forEach((key, entry) -> {
            attributes.put("baggage." + key, MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(entry.getValue())
                .build());
        });

        // Send message
        sqsClient.sendMessage(SendMessageRequest.builder()
            .queueUrl(queueUrl)
            .messageBody(toJson(message))
            .messageAttributes(attributes)
            .build());
    }

    private String getQueueUrl(String queueName) {
        String fullName = properties.getQueuePrefix() + "-" + queueName;
        return sqsClient.getQueueUrl(r -> r.queueName(fullName)).queueUrl();
    }

    private String toJson(Map<String, String> message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize message", e);
        }
    }
}
```

#### RedisMessagePublisherImpl.java (updated existing)
```java
@Service
@ConditionalOnProperty(name = "messaging.provider", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
public class RedisMessagePublisherImpl implements MessagePublisher {

    private final StringRedisTemplate redisTemplate;
    private final Tracer tracer;

    @Override
    public void publish(String queueName, Map<String, String> message) {
        // Add trace context to message
        Map<String, String> enrichedMessage = new HashMap<>(message);

        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            String traceparent = String.format("00-%s-%s-01",
                currentSpan.context().traceId(),
                currentSpan.context().spanId());
            enrichedMessage.put("traceparent", traceparent);
        }

        // Add baggage
        Baggage.current().forEach((key, entry) ->
            enrichedMessage.put("baggage." + key, entry.getValue()));

        // Publish to Redis stream
        redisTemplate.opsForStream().add(queueName, enrichedMessage);
    }
}
```

#### SqsMessageConsumerManager.java
```java
@Component
@ConditionalOnProperty(name = "messaging.provider", havingValue = "sqs")
@Slf4j
public class SqsMessageConsumerManager implements MessageConsumerManager {

    private final SqsClient sqsClient;
    private final SqsProperties properties;
    private final ObservationRegistry observationRegistry;
    private final ObjectMapper objectMapper;
    private final List<MessageHandler> handlers;  // Auto-discovered

    private final ExecutorService messageExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService pollExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, String> queueUrlCache = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public SqsMessageConsumerManager(
            SqsClient sqsClient,
            SqsProperties properties,
            ObservationRegistry observationRegistry,
            ObjectMapper objectMapper,
            List<MessageHandler> handlers) {
        this.sqsClient = sqsClient;
        this.properties = properties;
        this.observationRegistry = observationRegistry;
        this.objectMapper = objectMapper;
        this.handlers = handlers;
    }

    @Override
    public void start() {
        running = true;
        log.info("Starting SQS consumers for {} handlers", handlers.size());

        handlers.forEach(handler -> {
            String queueName = handler.getQueueName();
            var config = properties.getQueues().get(queueName);

            if (config != null && config.isEnabled()) {
                String queueUrl = getQueueUrl(queueName);
                pollExecutor.submit(() -> pollLoop(queueName, queueUrl, handler, config));
                log.info("Started SQS consumer for queue: {}", queueName);
            } else {
                log.info("Queue {} is disabled or not configured, skipping", queueName);
            }
        });
    }

    private void pollLoop(String queueName, String queueUrl, MessageHandler handler, QueueConfig config) {
        while (running) {
            try {
                var response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(config.getMaxMessages())
                    .waitTimeSeconds(config.getPollDelaySeconds())
                    .messageAttributeNames("All")
                    .build());

                for (var message : response.messages()) {
                    messageExecutor.submit(() -> processMessage(queueName, queueUrl, message, handler));
                }

            } catch (SqsException e) {
                log.error("SQS error polling queue {}: {}", queueName, e.getMessage());
                sleep(5000);
            } catch (Exception e) {
                log.error("Error polling queue {}", queueName, e);
                sleep(5000);
            }
        }
        log.info("SQS consumer stopped for queue: {}", queueName);
    }

    private void processMessage(String queueName, String queueUrl, Message message, MessageHandler handler) {
        Context traceContext = extractTraceContext(message.messageAttributes());
        Baggage baggage = extractBaggage(message.messageAttributes());

        try (Scope traceScope = traceContext.makeCurrent();
             Scope baggageScope = baggage.makeCurrent()) {

            Map<String, String> body = parseMessageBody(message.body());

            Observation.createNotStarted("sqs.message.process", observationRegistry)
                .contextualName("SQS Queue: " + queueName)
                .lowCardinalityKeyValue("queue", queueName)
                .highCardinalityKeyValue("message.id", message.messageId())
                .observe(() -> handler.handle(body));

            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());

            log.debug("Processed message {} from queue {}", message.messageId(), queueName);

        } catch (Exception e) {
            log.error("Failed to process message {} from queue {}, will retry",
                message.messageId(), queueName, e);
        }
    }

    @Override
    public void stop() {
        running = false;
        shutdownExecutors();
        log.info("SQS consumers stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void shutdownExecutors() {
        pollExecutor.shutdown();
        messageExecutor.shutdown();
        try {
            if (!pollExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                pollExecutor.shutdownNow();
            }
            if (!messageExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                messageExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pollExecutor.shutdownNow();
            messageExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private String getQueueUrl(String queueName) {
        return queueUrlCache.computeIfAbsent(queueName, name -> {
            String fullName = properties.getQueuePrefix() + "-" + name;
            return sqsClient.getQueueUrl(GetQueueUrlRequest.builder()
                .queueName(fullName)
                .build()).queueUrl();
        });
    }

    private Context extractTraceContext(Map<String, MessageAttributeValue> attrs) {
        if (!attrs.containsKey("traceparent")) {
            return Context.current();
        }
        String traceparent = attrs.get("traceparent").stringValue();
        return W3CTraceContextPropagator.extract(traceparent);
    }

    private Baggage extractBaggage(Map<String, MessageAttributeValue> attrs) {
        BaggageBuilder builder = Baggage.builder();
        attrs.forEach((key, value) -> {
            if (key.startsWith("baggage.")) {
                builder.put(key.substring(8), value.stringValue());
            }
        });
        return builder.build();
    }

    private Map<String, String> parseMessageBody(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse message body", e);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### Step 3: Create Common Interfaces

#### MessageHandler.java (provider-agnostic)
```java
/**
 * Provider-agnostic message handler. Handlers are auto-discovered
 * via List<MessageHandler> injection - no manual registration needed.
 */
public interface MessageHandler {
    /**
     * Queue/stream name this handler processes (e.g., "email-local-uploads")
     */
    String getQueueName();

    /**
     * Process the message. Exceptions trigger retry (message not acknowledged).
     */
    void handle(Map<String, String> message);
}
```

#### MessageConsumerManager.java
```java
/**
 * Common interface for Redis and SQS consumer managers.
 * Uses SmartLifecycle for proper startup/shutdown ordering.
 * Handlers are auto-discovered via constructor injection.
 */
public interface MessageConsumerManager extends SmartLifecycle {
    // SmartLifecycle provides: start(), stop(), isRunning(), getPhase()
    // No additional methods - handlers auto-discovered
}
```

#### MessagePublisher.java
```java
/**
 * Common interface for Redis and SQS publishers.
 */
public interface MessagePublisher {
    void publish(String queueName, Map<String, String> message);
}
```

### Step 4: Update RedisStreamManager → RedisMessageConsumerManager

Refactor existing `RedisStreamManager` to implement the common interface with auto-discovery:

```java
@Component
@ConditionalOnProperty(name = "messaging.provider", havingValue = "redis", matchIfMissing = true)
@Slf4j
public class RedisMessageConsumerManager implements MessageConsumerManager {

    private final RedisConnectionFactory connectionFactory;
    private final ObservationRegistry observationRegistry;
    private final RedisStreamsProperties streamsProperties;
    private final StreamHealthService healthService;
    private final List<MessageHandler> handlers;  // Auto-discovered

    private final Map<String, StreamMessageListenerContainer<String, MapRecord<String, String, String>>> containers = new ConcurrentHashMap<>();
    private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running = false;

    @Value("${spring.data.redis.stream.poll-timeout:2000}")
    private long pollTimeoutMs;

    @Value("${spring.data.redis.stream.batch-size:10}")
    private int batchSize;

    // Constructor injection - handlers auto-discovered by Spring
    public RedisMessageConsumerManager(
            RedisConnectionFactory connectionFactory,
            ObservationRegistry observationRegistry,
            RedisStreamsProperties streamsProperties,
            StreamHealthService healthService,
            List<MessageHandler> handlers) {
        this.connectionFactory = connectionFactory;
        this.observationRegistry = observationRegistry;
        this.streamsProperties = streamsProperties;
        this.healthService = healthService;
        this.handlers = handlers;
    }

    @Override
    public void start() {
        running = true;
        log.info("Starting Redis consumers for {} handlers", handlers.size());

        // Build lookup: queueName -> handler
        Map<String, MessageHandler> handlerMap = handlers.stream()
            .collect(Collectors.toMap(MessageHandler::getQueueName, h -> h));

        var streams = streamsProperties.getStreams();
        if (streams.isEmpty()) {
            log.info("No Redis Streams configured");
            healthService.setInitialized(true);
            return;
        }

        int total = countTotalConsumers(streams);
        healthService.setTotalConsumers(total);

        streams.forEach((streamKey, groups) ->
            groups.forEach((groupName, groupConfig) ->
                registerGroup(streamKey, groupName, groupConfig, handlerMap)));

        healthService.setInitialized(true);
        log.info("Redis consumers started: {}/{}", healthService.getRegisteredConsumers(), total);
    }

    private void registerGroup(String streamKey, String groupName,
                               RedisStreamsProperties.GroupConfig groupConfig,
                               Map<String, MessageHandler> handlerMap) {

        // Find handler by queue name
        MessageHandler handler = handlerMap.get(extractQueueName(streamKey));
        if (handler == null) {
            log.warn("No handler found for stream: {}", streamKey);
            return;
        }

        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
            StreamMessageListenerContainerOptions.builder()
                .executor(virtualThreadExecutor)
                .pollTimeout(Duration.ofMillis(pollTimeoutMs))
                .batchSize(batchSize)
                .errorHandler(t -> log.warn("Error in listener", t))
                .build();

        String containerKey = streamKey + ":" + groupName;
        var container = containers.computeIfAbsent(containerKey, key -> {
            ensureConsumerGroupExists(streamKey, groupName);
            return StreamMessageListenerContainer.create(connectionFactory, options);
        });

        // Register each consumer
        for (String consumerName : groupConfig.getConsumers()) {
            container.receive(
                Consumer.from(groupName, consumerName),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                createWrappedListener(streamKey, groupName, consumerName, handler)
            );
            healthService.incrementRegisteredConsumers();
        }

        if (!container.isRunning()) {
            container.start();
        }
    }

    private StreamListener<String, MapRecord<String, String, String>> createWrappedListener(
            String streamKey, String groupName, String consumerName, MessageHandler handler) {

        return record -> {
            Map<String, String> messageValue = record.getValue();
            Context finalContext = extractTraceContext(messageValue);

            try (Scope scope = finalContext.makeCurrent()) {
                Observation.createNotStarted("redis.stream.process", observationRegistry)
                    .contextualName("Redis Stream: " + streamKey)
                    .lowCardinalityKeyValue("stream", streamKey)
                    .lowCardinalityKeyValue("group", groupName)
                    .observe(() -> {
                        handler.handle(messageValue);
                        acknowledgeAndDelete(streamKey, groupName, record.getId());
                    });
            } catch (Exception e) {
                log.error("Error processing message {} from {}", record.getId(), streamKey, e);
            }
        };
    }

    @Override
    public void stop() {
        running = false;
        containers.values().forEach(StreamMessageListenerContainer::stop);
        log.info("Redis consumers stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE; // Start last, stop first
    }

    // ... existing helper methods (ensureConsumerGroupExists, extractTraceContext, etc.)
}
```

### Step 5: Adapt Existing Listeners to MessageHandler

Refactor existing `StreamListener` implementations to `MessageHandler`:

| Current | New |
|---------|-----|
| `EmailLocalUploadsStreamListener` | `EmailLocalUploadsHandler` |
| `AiProcessStreamListener` | `AiProcessHandler` |
| `VectorStoreIngestionListener` | `VectorStoreIngestionHandler` |
| `DocumentApprovedStreamListener` | `DocumentApprovedHandler` |
| `QuickBooksWebhookStreamListener` | `QuickBooksWebhookHandler` |
| `IntegrationPushStreamListener` | `IntegrationPushHandler` |

Example refactor:
```java
// Before: EmailLocalUploadsStreamListener
@Component("emailLocalUploadsStreamListener")
public class EmailLocalUploadsStreamListener
        implements StreamListener<String, MapRecord<String, String, String>> {

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        processAttachment(message.getValue());
    }
}

// After: EmailLocalUploadsHandler
@Slf4j
@Component
public class EmailLocalUploadsHandler implements MessageHandler {

    // Same dependencies injected via constructor...

    @Override
    public String getQueueName() {
        return "email-local-uploads";  // Matches stream key / SQS queue name
    }

    @Override
    public void handle(Map<String, String> message) {
        // Same business logic - now receives Map directly
        processAttachment(message);
    }

    // ... rest of processing logic unchanged
}
```

### Step 6: Update Configuration

**application.yml:**
```yaml
# Provider switch - controls which implementation is active
messaging:
  provider: redis  # Change to "sqs" when ready to switch

sqs:
  region: ${AWS_REGION:us-east-1}
  endpoint: ${SQS_ENDPOINT:}  # Empty for AWS, LocalStack URL for local
  queue-prefix: ${SQS_QUEUE_PREFIX:prod-tosspaper}

  queues:
    email-local-uploads:
      visibility-timeout-seconds: 300   # 5 min - S3 upload time
      max-receive-count: 3
      poll-delay-seconds: 20            # Long poll wait
      max-messages: 10

    ai-process:
      visibility-timeout-seconds: 600   # 10 min - AI extraction
      max-receive-count: 3
      poll-delay-seconds: 20
      max-messages: 5                   # Smaller batch for heavy processing

    vector-store-ingestion:
      visibility-timeout-seconds: 60    # 1 min - fast embeddings
      max-receive-count: 5
      poll-delay-seconds: 20
      max-messages: 10

    document-approved-events:
      visibility-timeout-seconds: 120   # 2 min
      max-receive-count: 3
      poll-delay-seconds: 20
      max-messages: 10

    quickbooks-events:
      visibility-timeout-seconds: 300   # 5 min - API calls
      max-receive-count: 5
      poll-delay-seconds: 20
      max-messages: 10

    integration-push-events:
      visibility-timeout-seconds: 600   # 10 min - dependency resolution
      max-receive-count: 5
      poll-delay-seconds: 20
      max-messages: 10
```

### Step 7: Switch to SQS

With the provider abstraction in place, migration is a single configuration change:

**Pre-switch checklist:**
1. All handlers implement `MessageHandler` interface
2. Both `RedisMessageConsumerManager` and `SqsMessageConsumerManager` tested
3. Both `RedisMessagePublisher` and `SqsMessagePublisher` tested
4. SQS queues and DLQs created in AWS
5. IAM permissions configured

**Switch procedure:**
1. Deploy application with both implementations (keep `messaging.provider: redis`)
2. Test SQS in staging environment (`messaging.provider: sqs`)
3. Monitor staging for 24-48 hours
4. Switch production to SQS (`messaging.provider: sqs`)
5. Monitor production
6. Rollback if issues: set `messaging.provider: redis` and restart

**Testing order (lowest to highest risk):**
1. `document-approved-events` - Simplest, idempotent
2. `email-local-uploads` - Low volume
3. `ai-process` - Medium complexity
4. `vector-store-ingestion` - Highest volume
5. `quickbooks-events` - External API
6. `integration-push-events` - Complex dependencies

### Step 8: Cleanup (Optional)

Remove after SQS is stable in production (keep for quick rollback initially):
- `RedisStreamPublisher.java`
- `RedisStreamPublisherImpl.java`
- `RedisStreamManager.java`
- `StreamListenerRegistry.java`
- `RedisStreamsProperties.java`
- `redis.streams` section from application.yml

## AWS Infrastructure Setup

### Create Queues (Terraform/CloudFormation)

```hcl
resource "aws_sqs_queue" "main" {
  for_each = var.queues

  name                       = "${var.environment}-tosspaper-${each.key}"
  visibility_timeout_seconds = each.value.visibility_timeout
  message_retention_seconds  = 1209600  # 14 days
  receive_wait_time_seconds  = 20

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = each.value.max_receive_count
  })
}

resource "aws_sqs_queue" "dlq" {
  for_each = var.queues

  name                      = "${var.environment}-tosspaper-${each.key}-dlq"
  message_retention_seconds = 1209600  # 14 days
}
```

### IAM Permissions

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "sqs:SendMessage",
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes",
        "sqs:GetQueueUrl"
      ],
      "Resource": "arn:aws:sqs:*:*:*-tosspaper-*"
    }
  ]
}
```

## Testing

### Docker Compose (Dev/Staging)

```yaml
# docker-compose.yml
services:
  localstack:
    image: localstack/localstack:3.0
    ports:
      - "4566:4566"
    environment:
      - SERVICES=sqs
      - DEBUG=1
      - PERSISTENCE=1
    volumes:
      - localstack-data:/var/lib/localstack
      - ./localstack/init-sqs.sh:/etc/localstack/init/ready.d/init-sqs.sh

volumes:
  localstack-data:
```

**Init script to create queues on startup:**
```bash
#!/bin/bash
# localstack/init-sqs.sh

QUEUES=(
  "email-local-uploads"
  "ai-process"
  "vector-store-ingestion"
  "document-approved-events"
  "quickbooks-events"
  "integration-push-events"
)

for queue in "${QUEUES[@]}"; do
  # Create DLQ
  awslocal sqs create-queue --queue-name "local-tosspaper-${queue}-dlq"

  # Get DLQ ARN
  DLQ_ARN=$(awslocal sqs get-queue-attributes \
    --queue-url "http://localhost:4566/000000000000/local-tosspaper-${queue}-dlq" \
    --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

  # Create main queue with DLQ
  awslocal sqs create-queue \
    --queue-name "local-tosspaper-${queue}" \
    --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}"

  echo "Created queue: local-tosspaper-${queue}"
done
```

### Application Config (Local)

```yaml
# application-local.yml
sqs:
  endpoint: http://localhost:4566
  region: us-east-1
  queue-prefix: local-tosspaper
```

### Integration Tests (Testcontainers)

```java
@Testcontainers
class SqsIntegrationTest {
    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:3.0")
    ).withServices(LocalStackContainer.Service.SQS);
}
```

### Test Cases

1. **Message flow** - Send message, verify handler receives it
2. **Trace propagation** - Verify traceparent survives round-trip
3. **DLQ routing** - Verify failed messages go to DLQ after maxReceiveCount
4. **Batching** - Verify batch operations work correctly
5. **Shutdown** - Verify graceful shutdown completes in-flight messages

## Monitoring

### CloudWatch Metrics

| Metric | Alert Threshold |
|--------|-----------------|
| `ApproximateNumberOfMessagesVisible` | > 10,000 (backlog) |
| `ApproximateAgeOfOldestMessage` | > 3600 seconds |
| DLQ `ApproximateNumberOfMessagesVisible` | > 0 (any failures) |

### Health Indicator

```java
@Component
public class SqsHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // Check DLQ message counts
        // Alert if any DLQ has messages
    }
}
```

## Rollback

### Provider Switch via @ConditionalOnProperty

Both Redis and SQS implementations remain in the codebase. Switch between them with a single property:

```yaml
messaging:
  provider: redis   # or "sqs"
```

**How it works:**

```java
// Redis - active when provider=redis (default)
@Component
@ConditionalOnProperty(name = "messaging.provider", havingValue = "redis", matchIfMissing = true)
public class RedisMessageConsumerManager implements MessageConsumerManager { }

// SQS - active when provider=sqs
@Component
@ConditionalOnProperty(name = "messaging.provider", havingValue = "sqs")
public class SqsMessageConsumerManager implements MessageConsumerManager { }
```

**Advantages:**
- Instant rollback via config change (no code deploy)
- Both implementations stay tested and production-ready
- No dual-write complexity
- Single property switches both publisher and consumer

### Procedure

1. Set `messaging.provider: redis` to rollback
2. Restart application
3. SQS messages retained for 14 days (can be replayed later when switching back)

## Files Changed

### New Files (Common Interfaces)
- `libs/models/src/main/java/com/tosspaper/models/messaging/MessageHandler.java`
- `libs/models/src/main/java/com/tosspaper/models/messaging/MessageConsumerManager.java`
- `libs/models/src/main/java/com/tosspaper/models/messaging/MessagePublisher.java`

### New Files (SQS Implementation)
- `libs/models/src/main/java/com/tosspaper/models/properties/SqsProperties.java`
- `libs/models/src/main/java/com/tosspaper/models/service/impl/SqsMessagePublisherImpl.java`
- `services/everything/src/main/java/com/tosspaper/everything/config/SqsConfig.java`
- `services/everything/src/main/java/com/tosspaper/everything/config/SqsMessageConsumerManager.java`
- `services/everything/src/main/java/com/tosspaper/everything/healthchecks/SqsHealthIndicator.java`

### New Files (Handlers - refactored from StreamListeners)
- `libs/email-engine/.../EmailLocalUploadsHandler.java`
- `libs/ai-engine/.../AiProcessHandler.java`
- `libs/ai-engine/.../VectorStoreIngestionHandler.java`
- `libs/api-tosspaper/.../DocumentApprovedHandler.java`
- `libs/integrations/.../QuickBooksWebhookHandler.java`
- `libs/integrations/.../IntegrationPushHandler.java`

### Modified Files
- `gradle/libs.versions.toml` - Add aws-sqs dependency
- `build.gradle` - Add SQS dependency
- `services/everything/src/main/resources/application.yml` - Add `messaging.provider` and SQS config
- `services/everything/.../RedisStreamManager.java` → `RedisMessageConsumerManager.java` (implements MessageConsumerManager)
- `libs/models/.../RedisStreamPublisher.java` → `RedisMessagePublisherImpl.java` (implements MessagePublisher)

### Deleted Files (after cleanup - Step 8)
- `services/everything/.../StreamListenerRegistry.java` (replaced by auto-discovery)
- Old `*StreamListener.java` files (replaced by `*Handler.java`)

### Files Kept for Rollback
- `RedisMessageConsumerManager.java` - Redis implementation stays for instant rollback
- `RedisMessagePublisherImpl.java` - Redis implementation stays for instant rollback
- `RedisStreamsProperties.java` - Configuration for Redis streams