# logged-lib

[![](https://jitpack.io/v/Fayupable/logged-lib.svg)](https://jitpack.io/#Fayupable/logged-lib)
[![CI](https://github.com/Fayupable/logged-lib/actions/workflows/ci.yml/badge.svg)](https://github.com/Fayupable/logged-lib/actions/workflows/ci.yml)

**Annotation-driven, AOP-based method observability for Java and Spring Boot — without ever logging your data.**

`@Logged` captures **that** a method was called, **how long** it took, whether it **succeeded or failed**, and **who called it**. It never captures the method's arguments or its return value. This is a deliberate, structural guarantee, not a configuration option someone has to remember to turn on.

```java
@Service
public class OrderService {

    @Logged(slowThresholdMs = 500, sampleRate = 1.0)
    public void placeOrder(Long userId, Long productId) {
        // ...
    }
}
```

```
[user:42] OrderService.placeOrder completed in 187ms
```

---

## Table of contents

- [Why this exists](#why-this-exists)
- [Installation](#installation)
- [Quick start](#quick-start)
- [What `@Logged` captures — and what it never does](#what-logged-captures--and-what-it-never-does)
- [Call chain tracking](#call-chain-tracking)
- [Async, `@Async`, and virtual thread support](#async-async-and-virtual-thread-support)
- [Structured logging (MDC)](#structured-logging-mdc)
- [JSON event emission](#json-event-emission)
- [Cross-service tracing over Kafka and RabbitMQ](#cross-service-tracing-over-kafka-and-rabbitmq)
- [Cross-service tracing over HTTP](#cross-service-tracing-over-http)
- [Where `@Logged` belongs](#where-logged-belongs)
- [Testing with logged-test](#testing-with-logged-test)
- [Configuration](#configuration)
- [Modules](#modules)
- [Metrics](#metrics)
- [Caller resolution](#caller-resolution)
- [Benchmarks](#benchmarks)
- [Quality gates](#quality-gates)
- [License](#license)

---

## Why this exists

Most method-logging libraries let you log arguments and return values, then bolt on masking rules to redact fields named `password` or `token`. That approach is only as safe as the masking configuration someone remembered to write — and someone will eventually forget, or add a new field the masking rules don't know about.

`logged-lib` takes a different position: the event model has **no field** for arguments or a return value at all. There is nothing to leak by omission, because there is nowhere for that data to go. The only detail ever captured about a failure is the exception's simple class name — never its message, since messages routinely carry the very data you're trying not to log (`"invalid password 'hunter2' for user"` is a real category of bug this design makes structurally impossible).

## Installation

Published via [JitPack](https://jitpack.io/).

Add the JitPack repository:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

Add the Spring Boot adapter (which transitively brings in `logged-core`):

```xml
<dependency>
    <groupId>com.github.fayupable</groupId>
    <artifactId>logged-spring</artifactId>
    <version>1.0.0</version>
</dependency>
```

Adding this dependency is enough. No further configuration, no beans to declare — see [Configuration](#configuration) for what's available if you want to change the defaults.

## Quick start

```java
public interface UserService {
    User getUser(Long id);
}

@Service
public class UserServiceImpl implements UserService {

    @Logged(slowThresholdMs = 300, sampleRate = 0.1)
    @Override
    public User getUser(Long id) {
        return repository.findById(id).orElseThrow();
    }
}
```

- `slowThresholdMs` — calls at or above this duration are always logged, regardless of sampling.
- `sampleRate` — the fraction of remaining (fast, successful) calls that get logged, evaluated independently per call. Failures are always logged, no matter the sample rate.

This is a **proxy-based** Spring AOP aspect, not full AspectJ weaving: it only intercepts calls made from outside the proxied bean. A method calling another `@Logged` method on `this` bypasses the proxy and is not intercepted — a well-known Spring AOP limitation, not a bug in this library.

## What `@Logged` captures — and what it never does

| Captured | Never captured |
|---|---|
| Class and method name | Method arguments |
| Timestamp and duration | Return value |
| Success / failure | Exception message |
| Caller identity (user id or IP) | Request headers, cookies, session data |
| Call-chain trace id and depth | |

## Call chain tracking

When a `@Logged` method calls another `@Logged` method — directly, or through several layers of separate beans — every call in that chain shares the same `traceId` and carries an increasing `depth`. This works across any number of nested calls, not just two:

```
[user:42] ServiceA.process completed in 42ms
[user:42] ServiceB.doWork completed in 12ms (trace=a1b2c3d4e5f6a7b8, depth=1)
[user:42] ServiceC.validate completed in 3ms (trace=a1b2c3d4e5f6a7b8, depth=2)
```

If a failure happens partway through a chain, the failing call and every call above it in the chain are marked as failed with the same `traceId`; calls that would have happened deeper in the chain simply never appear, since the chain stopped there. The deepest `FAILED` entry for a given `traceId` is exactly where the chain broke.

## Async, `@Async`, and virtual thread support

Call chain tracking, described above, is implemented with a `ThreadLocal`. That is a deliberate, cheap choice — but it means the tracked context only exists on the thread that set it. **The moment a `@Logged` method hands its work off to a different thread, that context does not follow automatically.** Without one of the fixes below, a `@Logged` call made from inside a `@Async` method, a manually submitted `Runnable`/`Callable`, or a `CompletableFuture`'s async stage starts a brand-new, disconnected chain: a new `traceId`, `depth` reset to zero, and no way to tell from the log output that it was ever related to the call that triggered it.

This is not specific to platform threads or thread pools in the traditional sense — it applies exactly the same way to `Executors.newVirtualThreadPerTaskExecutor()`. A virtual thread is still a distinct thread with its own `ThreadLocal` storage; nothing about virtual threads changes this problem or solves it on its own.

The fix is the same idea in every case: capture the context on the thread handing off the work, and restore it on the thread that actually runs it. This library ships two ready-made ways to do that, and one `CompletableFuture` behavior that requires no wiring at all.

### Wrapping your own `Executor`

If you submit work to an `Executor` or `ExecutorService` you manage directly — including a virtual-thread-per-task executor — wrap it once with `FlowContextPropagatingExecutor`:

```java
Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
Executor propagating = new FlowContextPropagatingExecutor(virtualThreadExecutor);

@Logged
public void processOrder(Order order) {
    propagating.execute(() -> {
        // Any @Logged call made in here is recognized as part of the
        // caller's chain, at the caller's depth — not a new chain.
        auditLogService.record(order);
    });
}
```

Every task submitted through the wrapped executor carries whatever context was active on the submitting thread at the moment `execute(...)` was called, and the executor thread's own state is always restored afterward — including when the submitted task throws.

### Wiring up Spring's `@Async`

`@Async` methods are dispatched by Spring's own `TaskExecutor` machinery, which your code never touches directly, so wrapping an `Executor` yourself does not apply here. Spring provides exactly the extension point this library needs — `TaskDecorator` — and this library ships an implementation of it:

```java
@Bean
public TaskExecutor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setTaskDecorator(new FlowContextTaskDecorator());
    executor.initialize();
    return executor;
}
```

Once this is registered, every `@Async` method dispatched through that executor carries the calling thread's chain forward. **This is not wired up automatically for every `TaskExecutor` bean in your application** — auto-attaching to any executor found in the application context would silently change the behavior of executors this library was never meant to touch. You decide which executor `@Logged` call chains should survive across, the same way you decide which methods get `@Logged` in the first place.

### `CompletableFuture`

A `@Logged` method that returns a `CompletableFuture` is detected and handled correctly **without any extra wiring**: this library measures the duration and outcome when the future actually completes, not when it is merely constructed and returned, so a method like this reports its real asynchronous duration and the real success/failure of the async work, not just how long it took to submit:

```java
@Logged(slowThresholdMs = 500, sampleRate = 1.0)
public CompletableFuture<Order> processOrderAsync(Order order) {
    return CompletableFuture.supplyAsync(() -> doWork(order), executor);
}
```

This part requires nothing from you. What still requires the wiring described above is whether a **nested** `@Logged` call made *inside* that async stage is recognized as part of the same chain — that depends on whatever `Executor` the async stage itself runs on:

- `CompletableFuture.supplyAsync(supplier, propagatingExecutor)` — pass a `FlowContextPropagatingExecutor` explicitly, and any `@Logged` call inside `supplier` joins the caller's chain.
- `CompletableFuture.supplyAsync(supplier)` (no executor argument) — runs on the shared, JVM-wide `ForkJoinPool.commonPool()`. This library will never wrap that pool automatically: it is shared by unrelated code throughout the JVM, and silently altering its behavior is exactly the kind of surprising, hard-to-diagnose side effect this library avoids elsewhere. If you need chain continuity here, always pass an explicit, wrapped executor instead of relying on the default.

### Carrying the current request and authenticated user across the same boundary

`FlowContextPropagatingExecutor` and `FlowContextTaskDecorator` only carry this library's own call-chain tracking and MDC across a thread hand-off. They deliberately do not also carry Spring Web's current HTTP request or Spring Security's authenticated user, so that they stay usable in applications that have neither on their classpath at all.

Without anything further, a **nested** `@Logged` call made from inside async work loses caller identity entirely on the executor thread: [caller resolution](#caller-resolution) falls back to an IP address, or `"unknown"`, even though the call's trace id and depth still propagate correctly. Two additional, equally optional decorators close this gap, one per concern:

| Decorator | Carries | Needs |
|---|---|---|
| `RequestAttributesPropagatingExecutor` / `RequestAttributesTaskDecorator` | The current HTTP request (`RequestContextHolder`) | Spring Web |
| `SecurityContextPropagatingExecutor` / `SecurityContextTaskDecorator` | The authenticated user (`SecurityContextHolder`) | Spring Security |

Nest whichever ones you need around the same delegate — order does not matter, since each one manages an independent `ThreadLocal`:

```java
Executor fullyPropagating = new FlowContextPropagatingExecutor(
        new SecurityContextPropagatingExecutor(
                new RequestAttributesPropagatingExecutor(realExecutor)));
```

For `@Async`, since `ThreadPoolTaskExecutor` only accepts a single `TaskDecorator`, compose them by nesting `decorate()` calls instead:

```java
@Bean
public TaskExecutor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setTaskDecorator(runnable ->
            new FlowContextTaskDecorator().decorate(
                    new SecurityContextTaskDecorator().decorate(
                            new RequestAttributesTaskDecorator().decorate(runnable))));
    executor.initialize();
    return executor;
}
```

This is a one-time setup step, written once wherever you define the executor bean — not something repeated at every call site.

## Structured logging (MDC)

While a `@Logged` method runs, this library writes four keys into the current thread's SLF4J [MDC](https://www.slf4j.org/api/org/slf4j/MDC.html):

| Key | Value |
|---|---|
| `logged.traceId` | the call chain's trace id (see [Call chain tracking](#call-chain-tracking)) |
| `logged.depth` | the current call's depth within its chain |
| `logged.className` | the class name resolved for the current invocation |
| `logged.methodName` | the method name resolved for the current invocation |

This is separate from, and in addition to, the summary line this library's own `InvocationEventEmitter` produces. Its real value is that **your own log statements**, written from inside a `@Logged` method, automatically pick up these fields too — without threading any of this information through by hand:

```java
@Logged(slowThresholdMs = 500, sampleRate = 1.0)
public void placeOrder(Long userId, Long productId) {
    log.info("Stock check started");   // your own, ordinary log statement
    checkStock(productId);
    log.info("Payment charged");       // your own, ordinary log statement
    chargePayment(userId);
}
```

With a Logback pattern (or JSON encoder) that includes the MDC, every one of those lines — not just this library's own summary — carries the same `logged.traceId`:

```xml
<pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg [traceId=%X{logged.traceId}]%n</pattern>
```

```
10:00:01.001 INFO  OrderService - Stock check started [traceId=fd6a56d153407322]
10:00:01.045 INFO  OrderService - Payment charged [traceId=fd6a56d153407322]
10:00:01.052 INFO  Slf4jInvocationEventEmitter - [user:42] OrderService.placeOrder completed in 187ms [traceId=fd6a56d153407322]
```

In Grafana/Loki, filtering on `{logged_traceId="fd6a56d153407322"}` now returns every line involved in that single call — your own business logs and this library's summary line together, in order — instead of only the one line this library emits itself.

Keys are prefixed with `logged.` specifically to avoid colliding with an MDC key your application, or another library, already uses (a bare `traceId` key is common enough that a collision is likely without this prefix).

This can be disabled entirely, if you manage MDC yourself or want to avoid the extra `MDC.put`/`MDC.remove` calls on a very hot path:

```yaml
logged:
  mdc:
    enabled: false
```

`FlowContextPropagatingExecutor` and `FlowContextTaskDecorator` (see [Async, `@Async`, and virtual thread support](#async-async-and-virtual-thread-support)) propagate the *entire* MDC context map across a thread hand-off, not only these four keys — so any MDC entries your own code has set (a request id from a web filter, for example) survive a thread boundary the same way this library's own do.

## JSON event emission

The default `Slf4jInvocationEventEmitter` writes a human-readable prose line, which is easy to read at a terminal but awkward for a structured logging backend (Loki, Elasticsearch, Datadog, and similar) that parses each log line as JSON: the whole line either fails to parse or gets indexed as one opaque string field, so you cannot filter or aggregate on individual fields such as `className` or `success`.

`JsonInvocationEventEmitter` is a drop-in alternative that writes the exact same `MethodInvocationEvent` fields as a single line of JSON instead:

```json
{"className":"OrderService","methodName":"placeOrder","timestamp":"2026-08-04T10:00:01.052Z","durationMs":187,"success":true,"exceptionType":null,"rootCauseType":null,"callerIdentity":"user:42","traceId":"fd6a56d153407322","depth":0}
```

Register it in place of the default:

```java
@Bean
InvocationEventEmitter invocationEventEmitter() {
    return new JsonInvocationEventEmitter();
}
```

No JSON library is used or required — every field on `MethodInvocationEvent` is a primitive, a `String`, or an `Instant`, so a small hand-written writer is enough, and this stays true to the rest of this library: no dependency is added to pick this up. String fields are escaped for quotes, backslashes, and control characters before being written, so the emitted line is always valid JSON regardless of what a class or method name happens to contain.

Like the default emitter, successful calls are logged at `INFO` and failed calls at `WARN`, and the exception message is never included — only its simple class name, consistent with everything else this library records.

## Cross-service tracing over Kafka and RabbitMQ

Everything above — call chain tracking, MDC, async propagation — works within a single service. A call chain that crosses a message queue into a different service starts over with a brand-new `traceId` on the other side by default: nothing carries it across a Kafka or RabbitMQ message on its own.

`KafkaTraceHeaderCarrier` and `RabbitTraceHeaderCarrier` close that gap by writing the current `traceId`/depth into the message's **headers** — never into its body — when publishing, and reading them back when consuming:

```java
// Producing (Kafka)
ProducerRecord<String, OrderEvent> record = new ProducerRecord<>("orders", event);
KafkaTraceHeaderCarrier.writeToHeaders(record.headers());
kafkaTemplate.send(record);
```

```java
// Consuming (Kafka)
@KafkaListener(topics = "orders")
public void onOrderPlaced(ConsumerRecord<String, OrderEvent> record) {
    KafkaTraceHeaderCarrier.readAndAdopt(record.headers(), () ->
            inventoryService.reserveStock(record.value()));
}
```

```java
// Publishing (RabbitMQ)
MessageProperties properties = new MessageProperties();
RabbitTraceHeaderCarrier.writeToHeaders(properties);
rabbitTemplate.send(exchange, routingKey, new Message(body, properties));
```

```java
// Consuming (RabbitMQ)
@RabbitListener(queues = "notifications")
public void onNotificationRequested(Message message) {
    RabbitTraceHeaderCarrier.readAndAdopt(message.getMessageProperties(), () ->
            notificationService.sendPushNotification(message));
}
```

The message's payload — its schema, its serialization format (JSON, Avro, protobuf, or anything else) — is never touched. If a message carries no trace headers (published by a service that does not use this library, for example), `readAndAdopt` simply runs the given work as-is; any `@Logged` call inside it then starts a new chain of its own, exactly as if this class were not involved at all.

Both are optional and depend only on `org.apache.kafka:kafka-clients`' `Headers` interface and Spring AMQP's `MessageProperties`, respectively — not `spring-kafka` specifically, so this works whether you produce/consume with `KafkaTemplate`/`@KafkaListener` or a plain `KafkaProducer`/`KafkaConsumer`. Both dependencies are `provided` scope in `logged-spring`: they are needed to compile this library, but are never forced onto a consuming application's own dependency tree, and neither `KafkaTraceHeaderCarrier` nor `RabbitTraceHeaderCarrier` is wired in automatically anywhere — call them explicitly, exactly where you already publish or consume a message.

**What crosses the queue, and what does not.** Only the call chain's `traceId` and `depth` cross a Kafka or RabbitMQ message — not the full MDC context map, and not the authenticated caller. This is a deliberate scope boundary, not an oversight: [MDC propagation](#structured-logging-mdc) and [caller identity propagation](#async-async-and-virtual-thread-support) both exist to carry state across a boundary that is still logically the *same* request (a thread hand-off within one service). A message consumed from a queue is a fundamentally different kind of boundary — it starts a new unit of work, generally with no single authenticated "caller" of its own, so `@Logged` methods on the consuming side resolve their own caller identity independently (typically `"unknown"`, since a queue consumer thread has no HTTP request or Spring Security context unless your own listener code establishes one). The `traceId` still lets you correlate that consumer-side log output with the producing service's, even though the two sides may report different caller identities.

## Cross-service tracing over HTTP

The synchronous counterpart to Kafka/RabbitMQ propagation: when one service calls another directly over HTTP (a Feign client, `RestTemplate`, or `WebClient`), the `traceId` crosses in the request's headers, under `X-Logged-Trace-Id`/`X-Logged-Depth` — never in the request body.

**Outbound** — pick whichever client you use:

```java
// RestTemplate
restTemplate.getInterceptors().add(new HttpTraceClientHttpRequestInterceptor());
```

```java
// Feign — Spring Cloud OpenFeign auto-detects any RequestInterceptor bean
@Bean
public RequestInterceptor loggedTraceRequestInterceptor() {
    return new FeignTraceRequestInterceptor();
}
```

```java
// WebClient
WebClient webClient = WebClient.builder()
        .filter(new HttpTraceExchangeFilterFunction())
        .build();
```

**Inbound** — the receiving service reads the same headers back, so its own `@Logged` calls join the caller's chain instead of starting a new one:

```java
@Bean
public FilterRegistrationBean<HttpTraceServletFilter> httpTraceServletFilter() {
    FilterRegistrationBean<HttpTraceServletFilter> registration =
            new FilterRegistrationBean<>(new HttpTraceServletFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
}
```

All four are opt-in, exactly like every other propagation class in this library — none is wired in automatically. `RestTemplate` and Feign are purely synchronous, so there is no ambiguity about which thread's context is captured; it is always the calling thread's.

**`WebClient` has one caveat worth knowing.** It is reactive: `HttpTraceExchangeFilterFunction` runs whenever the request is actually subscribed to, which — if composed with `subscribeOn`/`publishOn` — may not be the same thread that built the call in your code. For the common case of a `@Logged` method calling `WebClient` and blocking on the result (`.block()`), the subscribing thread and the calling thread are the same, and this behaves exactly like `RestTemplate`/Feign. Full correctness across an arbitrarily composed reactive chain would require bridging `FlowContext` through Reactor's own `Context`, which this library does not do (see [Where `@Logged` belongs](#where-logged-belongs) for the related `Mono`/`Flux` return type limitation).

**A note on trusting the incoming `X-Logged-Trace-Id` header.** `HttpTraceHeaderCarrier.readAndAdopt` only accepts a value shaped like a trace id this library could actually have produced itself (a short hexadecimal string); anything else — including an attempt to inject control characters to forge fake log lines — is rejected outright and treated as no header at all. What this validation does **not** do is verify that the value actually originated from one of your own services: a well-formed but fabricated `X-Logged-Trace-Id` (for example `aabbccdd11223344`) still passes it, exactly the way a forged `X-Forwarded-For` value would. This cannot be closed without a fundamentally heavier mechanism (cryptographically signing the trace id), which would be disproportionate for what is meant to be a lightweight correlation id, not a security token — the trace id is never evaluated, executed, or used for any authorization decision, so the worst a fabricated value can do is make a caller's own log output appear correlated under a trace id it does not legitimately belong to (a data-integrity concern for your own observability, not a code-execution or data-exposure one). If `HttpTraceServletFilter` is registered on an endpoint directly reachable by untrusted external callers, treat the incoming trace id the same way this library already treats `X-Forwarded-For`: as caller-supplied, not authenticated. Only register it where the caller is another of your own services (Feign/`RestTemplate`/`WebClient` calls from within your own application boundary), or strip/validate this header at your gateway for traffic you do not trust.

**Using a different HTTP client entirely?** All four classes above are thin wrappers around a lower-level, public `HttpTraceHeaderCarrier`, which operates on plain method references rather than one specific header type, so it works with any client that exposes a `(name, value) -> void` header-writing method and a `(name) -> String` header-reading method:

```java
// Any HTTP client with its own header API
HttpTraceHeaderCarrier.writeToHeaders(connection::setRequestProperty);
HttpTraceHeaderCarrier.readAndAdopt(connection::getHeaderField, () -> handleRequest());
```

## Where `@Logged` belongs

`@Logged` is meant for **service and application-layer methods**, and for **adapters calling external systems** (a Feign client checking stock before an order is placed, for example), where knowing that an operation ran, how long it took, and whether it failed is operationally useful.

It is generally redundant on **controllers** — HTTP access logs already cover that layer — and it **cannot** be applied to JPA entities, `@Repository` beans, or Spring Data repository implementations at all. A startup-time guard rejects the application if it tries:

```
IllegalStateException: @Logged is not allowed on class com.example.UserRepositoryImpl
(bean 'userRepositoryImpl'): entities, @Repository beans, and Spring Data repository
implementations must not carry method-level observability instrumentation. Move @Logged
to the service or application layer method that calls this bean instead.
```

This is deliberate: entities can be invoked at a frequency (JSON serialization, JPA dirty checking) that would drown out everything else, and repositories are already covered by lower-level persistence metrics.

**`@Logged` also cannot be applied to a method returning a reactive `Publisher`** (Project Reactor's `Mono`/`Flux`, or an RxJava adapter implementing the same interface) — the same startup guard rejects this too:

```
IllegalStateException: @Logged is not supported on method com.example.OrderService#placeOrder
(bean 'orderService'): it returns reactor.core.publisher.Mono, a reactive publisher. Recording
would measure only how long the pipeline took to assemble, not the actual asynchronous work,
and would always report success even if the pipeline later fails. Use CompletableFuture instead,
or remove @Logged from this method until reactive support is added.
```

This is deliberate too, for a different reason than entities/repositories: a reactive type is lazy — nothing runs until something subscribes — so by the time `@Logged` could record anything, the pipeline has only been assembled, not executed. Recording at that point would silently report a near-zero duration and unconditional success no matter what the pipeline actually does once subscribed to, which is worse than not measuring it at all, since it looks like real data. Rejecting it at startup is preferred over recording something misleading. `@Logged` already supports `CompletableFuture` correctly (see [Async, `@Async`, and virtual thread support](#async-async-and-virtual-thread-support)); full reactive support (bridging `FlowContext` and MDC through Reactor's `Context`) is a substantially larger feature and not yet implemented.

## Testing with logged-test

Asserting that `@Logged` actually observed a call correctly has historically meant either capturing and parsing log output, or hand-rolling a throwaway `InvocationEventEmitter`/`MetricsRecorder`/`IClientInfoPort` in every project that depends on this library. `logged-test` ships ready-made, thread-safe in-memory implementations of all three output ports instead, so a test can assert directly on Java objects.

Add it as a test-scoped dependency:

```xml
<dependency>
    <groupId>com.fayupable</groupId>
    <artifactId>logged-test</artifactId>
    <version>1.1.0</version>
    <scope>test</scope>
</dependency>
```

Register the ones you need as `@TestConfiguration` beans — `@ConditionalOnMissingBean` in `LoggedAutoConfiguration` picks them up in place of the real `Slf4jInvocationEventEmitter`/`MicrometerMetricsRecorder`/caller resolver automatically:

```java
@TestConfiguration
class LoggedTestConfig {

    @Bean
    InvocationEventEmitter invocationEventEmitter() {
        return new InMemoryInvocationEventEmitter();
    }

    @Bean
    MetricsRecorder metricsRecorder() {
        return new InMemoryMetricsRecorder();
    }

    @Bean
    IClientInfoPort clientInfoPort() {
        return new InMemoryClientInfoPort("user:42");
    }
}

@SpringBootTest
@Import(LoggedTestConfig.class)
class OrderServiceTest {

    @Autowired InMemoryInvocationEventEmitter emitter;
    @Autowired OrderService orderService;

    @Test
    void recordsSuccessfulOrderProcessing() {
        orderService.processOrder(order);

        MethodInvocationEvent event = emitter.lastEvent();
        assertThat(event.methodName()).isEqualTo("processOrder");
        assertThat(event.success()).isTrue();
        assertThat(event.callerIdentity()).isEqualTo("user:42");
    }
}
```

The same three classes work without Spring at all, against a `LoggedAspect` (or any other interceptor) constructed directly in a plain JUnit test — `logged-test` depends on nothing but `logged-core`.

Each class exposes `clear()` so a single instance can be reused across test methods without leaking state between them, and is backed by a `CopyOnWriteArrayList` so recording from a different thread — for example inside work wrapped by `FlowContextPropagatingExecutor` — never loses an event or corrupts the recorded list.

## Configuration

Every property below is optional and defaults to preserving the library's out-of-the-box behavior unmodified.

| Property | Default | Effect |
|---|---|---|
| `logged.enabled` | `true` | Global switch. Setting this to `false` removes the aspect entirely; `@Logged` methods run uninstrumented. |
| `logged.metrics.enabled` | `true` | Setting this to `false` falls back to a no-op metrics recorder even when a `MeterRegistry` bean is present. |
| `logged.client-info.trust-forwarded-headers` | `false` | Whether the caller-IP resolver may trust the client-controlled `X-Forwarded-For` header. Only enable this after confirming the application sits behind a proxy that strips and re-sets this header itself — see [Caller resolution](#caller-resolution). |
| `logged.mdc.enabled` | `true` | Whether `logged.traceId`/`logged.depth`/`logged.className`/`logged.methodName` are written to the current thread's MDC during a `@Logged` call — see [Structured logging (MDC)](#structured-logging-mdc). |

```yaml
logged:
  client-info:
    trust-forwarded-headers: true
```

## Modules

| Module | Depends on | Published |
|---|---|---|
| `logged-core` | nothing | ✅ |
| `logged-spring` | `logged-core`, Spring Boot (all `provided`) | ✅ |
| `logged-test` | `logged-core` | ✅ |
| `logged-benchmarks` | `logged-core`, `logged-spring` | ❌ internal only |

- **`logged-core`** is framework-free: the `@Logged` annotation, the `MethodInvocationEvent`/`FlowContext` models, and the output ports (`InvocationEventEmitter`, `MetricsRecorder`, `IClientInfoPort`), each with a no-op default. It has zero dependencies and can be used standalone by any interception mechanism — not only Spring AOP.
- **`logged-spring`** is the Spring Boot adapter: the `@Around` aspect, an SLF4J-backed emitter, a Micrometer-backed metrics recorder, a Spring Security-backed caller resolver, the startup guard described above, full auto-configuration, the `FlowContextPropagatingExecutor`/`FlowContextTaskDecorator` pair and the `RequestAttributes`/`SecurityContext` propagating decorators described in [Async, `@Async`, and virtual thread support](#async-async-and-virtual-thread-support), the MDC propagation described in [Structured logging (MDC)](#structured-logging-mdc), the `KafkaTraceHeaderCarrier`/`RabbitTraceHeaderCarrier` pair described in [Cross-service tracing over Kafka and RabbitMQ](#cross-service-tracing-over-kafka-and-rabbitmq), and the HTTP integrations (`HttpTraceClientHttpRequestInterceptor`, `FeignTraceRequestInterceptor`, `HttpTraceExchangeFilterFunction`, `HttpTraceServletFilter`, and the underlying `HttpTraceHeaderCarrier`) described in [Cross-service tracing over HTTP](#cross-service-tracing-over-http). Every Spring/Micrometer/Security/Kafka/RabbitMQ/Feign/WebFlux dependency it declares is `provided` scope, so none of it is forced onto a consuming project's dependency tree — if your application doesn't already have Micrometer, Spring Security, Kafka, RabbitMQ, Feign, or WebFlux, the corresponding feature simply falls back to a no-op, or is simply never invoked.
- **`logged-test`** provides in-memory test doubles for all three output ports, described in [Testing with logged-test](#testing-with-logged-test). It depends only on `logged-core`, so it works whether or not `logged-spring` is on the classpath.
- **`logged-benchmarks`** never leaves this repository; see [Benchmarks](#benchmarks).

## Metrics

When a `MeterRegistry` bean is present (and `logged.metrics.enabled` is not set to `false`), every invocation — sampled or not — is recorded against three meters:

| Meter | Type | Tags |
|---|---|---|
| `method.invocations` | Counter | `class`, `method`, `outcome` (`success`/`error`) |
| `method.duration` | Timer, with percentile histogram | `class`, `method` |
| `method.errors` | Counter, registered only on failure | `class`, `method`, `exception` |

Tags are deliberately bounded to this small, fixed set. Method arguments and return values are never used as tags, since that would produce an unbounded number of time series and degrade the metrics backend.

Whether a `MeterRegistry` bean turns out to exist is checked lazily, on the first `@Logged` invocation, rather than during Spring Boot auto-configuration itself — this sidesteps auto-configuration ordering entirely, so metrics work correctly whether Micrometer is wired up by Actuator, a manually declared bean, or anything else.

### Securing the metrics endpoint

If you expose these metrics through Spring Boot Actuator (`management.endpoints.web.exposure.include: metrics`), remember that Actuator endpoints are **not exposed by default** — this is an explicit opt-in in your own application. Once exposed, treat `/actuator/metrics` like any other operational endpoint: it reveals class and method names, call outcomes, and exception types (never arguments, return values, or exception messages, consistent with the rest of this library), which is roughly the same risk profile as Spring Boot's own built-in `http.server.requests` metric. In production, restrict Actuator behind authentication, a separate management port, or network-level access control, exactly as you would for any other operational endpoint — this is a standard Spring Boot deployment concern, not something specific to this library.

## Caller resolution

The default `IClientInfoPort` (active whenever Spring Security and Spring Web are both on the classpath) resolves the caller as:

1. The authenticated Spring Security principal's name (`user:42`), if one is present and it isn't the anonymous principal.
2. Otherwise, the current HTTP request's `getRemoteAddr()` (`ip:203.0.113.5`).
3. `unknown`, if neither is available (for example, a scheduled job with no active request).

The client IP is read from `getRemoteAddr()`, **not** from `X-Forwarded-For`, by default. That header is controlled by the client and can be forged by anyone unless the application sits behind a proxy configured to strip and re-set it — a deployment detail this library cannot know on its own. If your application does sit behind such a proxy, set `logged.client-info.trust-forwarded-headers=true` explicitly, mirroring how Spring Security itself requires trusted proxies to be declared rather than assumed.

## Benchmarks

`LoggedAspect`'s overhead is measured with [JMH](https://openjdk.org/projects/code-tools/jmh/), comparing a direct method call against the same call made through a `@Logged`-intercepted proxy, with every port wired to its no-op implementation. This isolates the cost of proxy dispatch, `FlowContext` `ThreadLocal` management, and the emission/metrics decision path, from the cost of any actual logging or metrics backend I/O.

Measured on JDK 21.0.11 (Amazon Corretto), average time per operation, 2 JVM forks × 5 warmup + 5 measurement iterations each (10 samples per benchmark):

| Benchmark | Score | Error (99.9% CI) | Unit |
|---|---|---|---|
| `baseline` (direct call, no interception) | 0.681 | ± 0.025 | ns/op |
| `logged` (through `@Logged`, no-op ports) | 960.310 | ± 33.785 | ns/op |

The aspect adds roughly **~1 microsecond** per intercepted call. For context, a typical database query or HTTP call — the kind of operation `@Logged` is meant to instrument — takes anywhere from hundreds of microseconds to several milliseconds, making this overhead three to four orders of magnitude smaller than the operation it wraps. In practice, it is not observable outside of a microbenchmark.

Reproduce it yourself:

```bash
mvn -pl logged-benchmarks package
java -jar logged-benchmarks/target/benchmarks.jar
```

Results will vary by hardware and JVM. The benchmark class lives at `logged-benchmarks/src/main/java/com/fayupable/logged/benchmarks/LoggedAspectBenchmark.java`.

## Quality gates

Every module is covered by real, behavior-driven tests — Spring AOP proxies exercised through `AspectJProxyFactory`, real `ApplicationContextRunner` contexts for auto-configuration, real `SimpleMeterRegistry` and Logback `ListAppender` instances for metrics and log output, never mocks standing in for the thing actually being verified.

- **Tests**: 209 across both modules, `mvn test`.
- **Mutation testing** ([Pitest](https://pitest.org/)): `logged-core` at 100%, `logged-spring` at 98%, enforced via `mvn verify`.
- **Style** ([Checkstyle](https://checkstyle.org/)): 0 violations, a small rule set (unused/star imports, missing braces, unreachable line lengths) chosen to catch real mistakes without dictating subjective formatting.

```bash
mvn verify
```

runs tests, mutation testing, and style checks together.

## License

[Apache License 2.0](LICENSE).
