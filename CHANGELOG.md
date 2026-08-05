# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.2.0] - 2026-08-04

### Added

- New `logged-test` module: in-memory, thread-safe test doubles for all three output ports — `InMemoryInvocationEventEmitter`, `InMemoryMetricsRecorder` (backed by a new `RecordedMetric` record), and `InMemoryClientInfoPort` — so a consuming project can assert directly on `@Logged` behavior in tests instead of parsing log output or hand-rolling its own stubs. Depends only on `logged-core`, so it works with or without `logged-spring` on the classpath.
- New `JsonInvocationEventEmitter` (`logged-spring`): writes each `MethodInvocationEvent` as a single line of structured JSON through SLF4J instead of `Slf4jInvocationEventEmitter`'s human-readable line, for applications shipping logs to Loki/Elasticsearch/Datadog and similar backends that parse each line as JSON. Uses a small hand-written JSON writer with proper string escaping instead of adding a JSON library dependency.

## [1.1.0] - 2026-08-03

### Added

- Async and virtual thread support: `FlowContextPropagatingExecutor` (wraps any `Executor`, including `Executors.newVirtualThreadPerTaskExecutor()`) and `FlowContextTaskDecorator` (Spring `@Async`/`TaskExecutor` integration) carry a call chain's `FlowContext` across a thread hand-off, which a `ThreadLocal`-backed context does not do on its own.
- `LoggedAspect` now detects a `@Logged` method returning `CompletableFuture` and records duration/outcome when the future actually completes (`whenComplete`), instead of at submission time; `CompletionException` wrapping from a chained stage (`thenApply`, etc.) is unwrapped to report the real exception type.
- MDC structured logging: `LoggedMdcKeys` (`logged.traceId`, `logged.depth`, `logged.className`, `logged.methodName`) are written to the current thread's MDC for the duration of a `@Logged` call, so an application's own log statements made from inside that call automatically carry the same fields in a structured logging backend (Loki, ELK) — not only this library's own summary line. Configurable via `logged.mdc.enabled` (default `true`). `FlowContextPropagatingExecutor` and `FlowContextTaskDecorator` propagate the full MDC context map across a thread hand-off as well, not just this library's own keys.
- `MethodInvocationEvent` gains `rootCauseType`, resolved by walking `Throwable#getCause()` to the deepest cause, surfacing the original failure type when a framework wraps it in a generic exception.
- `RequestAttributesPropagatingExecutor`/`RequestAttributesTaskDecorator` and `SecurityContextPropagatingExecutor`/`SecurityContextTaskDecorator`: carry the current Spring Web HTTP request and the authenticated Spring Security user, respectively, across a thread hand-off. Without these, a nested `@Logged` call made from inside work wrapped only by `FlowContextPropagatingExecutor`/`FlowContextTaskDecorator` correctly keeps its trace id and depth but silently loses caller identity on the executor thread. Both are opt-in and composable by nesting around the same delegate executor, independently of `FlowContextPropagatingExecutor`/`FlowContextTaskDecorator`.
- Cross-service tracing: `KafkaTraceHeaderCarrier` and `RabbitTraceHeaderCarrier` write the current call chain's `traceId`/depth into a Kafka or RabbitMQ message's headers when publishing, and read them back to adopt the same chain when consuming, so a `traceId` now correlates log output across service boundaries connected by a message queue, not only within a single JVM. Both depend only on `org.apache.kafka:kafka-clients`' `Headers` interface and Spring AMQP's `MessageProperties` (both `provided` scope in `logged-spring`), are opt-in like every other propagation class in this library, and never touch the message's own payload/schema. `FlowContextCarrier` (`com.fayupable.logged.spring.aspect`) is the new, narrow public entry point these two build on to read/write the active `FlowContext` from outside the `aspect` package, without exposing the rest of the package-private `FlowContextHolder`.
- `LoggedTargetGuardBeanPostProcessor` now also rejects a `@Logged` method returning a reactive `org.reactivestreams.Publisher` (Project Reactor's `Mono`/`Flux`, or an RxJava adapter implementing the same interface), failing application startup with a clear message instead of silently recording a near-zero duration and unconditional success for a pipeline that has only been assembled, not executed, by the time `@Logged` could record anything. Detected via reflection against the interface name only, so this adds no dependency on Reactor/RxJava/reactive-streams to `logged-spring`.
- Cross-service tracing over HTTP: `HttpTraceClientHttpRequestInterceptor` (`RestTemplate`), `FeignTraceRequestInterceptor` (Feign), `HttpTraceExchangeFilterFunction` (`WebClient`), and `HttpTraceServletFilter` (inbound) carry the current call chain's `traceId`/depth across a synchronous HTTP call, under `X-Logged-Trace-Id`/`X-Logged-Depth` headers, completing the cross-service tracing story alongside Kafka/RabbitMQ. All four are opt-in and built on a new, lower-level public `HttpTraceHeaderCarrier`, which operates on plain method references (`BiConsumer`/`Function`) rather than one concrete header type, so any HTTP client — not only the four integrated here — can participate by pointing it at that client's own header-writing/reading methods. `HttpTraceExchangeFilterFunction`'s Javadoc documents a caveat specific to `WebClient`'s reactive nature: it captures whichever thread's `FlowContext` is active when the request is actually subscribed to, which is not guaranteed to be the calling thread's if the request is composed with `subscribeOn`/`publishOn`.
- `HttpTraceHeaderCarrier`, `KafkaTraceHeaderCarrier`, and `RabbitTraceHeaderCarrier` now validate an incoming trace id against the exact shape this library itself ever produces (a short hexadecimal string) before adopting it, rejecting anything else exactly like a missing header. This closes a log-forgery vector specific to the HTTP carrier: without it, an untrusted caller of a publicly reachable endpoint could set `X-Logged-Trace-Id` to a value containing control characters (for example a newline) crafted to inject a fabricated line into log output. This does not, and cannot, verify that a well-formed trace id actually originated from a trusted caller — see the "trusting the incoming `X-Logged-Trace-Id` header" note in the README for the residual, unavoidable trust boundary this leaves.

### Changed

- `LoggedAspect` now resolves the reported class name from `ProceedingJoinPoint#getTarget()` instead of the join point signature, so it reports the concrete implementing class rather than the interface `@Logged` is declared on.
- Class and method name resolution is now cached per `(target class, method)` pair, avoiding repeated reflection on every invocation of the same method.
- `MicrometerMetricsRecorder`'s internal meter caches now key on dedicated records (`InvocationCounterKey`, `DurationTimerKey`, `ErrorCounterKey`) instead of concatenated strings, removing a theoretical cache-key collision when `className` is a fully qualified name.
- `LoggedAspect.logInvocation` now restores the MDC and closes the flow scope from a single `finally` block instead of duplicating that pair of calls at three call sites, so it also runs if a collaborator (`MetricsRecorder`/`InvocationEventEmitter`) throws an `Error` rather than a `RuntimeException`.
- `LoggedTargetGuardBeanPostProcessor` now walks a bean's superclass chain when checking for a `@Logged` method, instead of only its declared methods, so a `@Logged` method inherited from an abstract base class can no longer bypass the guard on a disallowed subclass.
- `LazyMetricsRecorder` no longer permanently caches a "no `MeterRegistry` available" outcome; it retries on every call until a registry is found, instead of staying pinned to a no-op recorder for the application's lifetime if the very first `@Logged` invocation raced ahead of the registry bean's creation.
- `EmissionPolicy` now clamps `Logged#sampleRate()` to `[0.0, 1.0]` before using it, making out-of-range values (`> 1.0` or `< 0.0`) behave as an explicit, defined "always"/"never" instead of relying on `ThreadLocalRandom`'s specific range.

## [1.0.0] - 2026-07-24

### Added

- `@Logged` annotation (`logged-core`): method-level observability marker with `slowThresholdMs` and `sampleRate`, restricted to methods only.
- `MethodInvocationEvent` and `FlowContext` models capturing structural call metadata only — no method arguments, no return values.
- Output ports (`InvocationEventEmitter`, `MetricsRecorder`, `IClientInfoPort`) with no-op defaults, allowing `logged-core` to be used standalone by any interception mechanism.
- `LoggedAspect` (`logged-spring`): proxy-based Spring AOP `@Around` interceptor, composed of `FlowContextHolder` (call-chain tracking via `ThreadLocal`) and `EmissionPolicy` (sampling/threshold emission decision).
- Call chain tracking: nested `@Logged` calls across separate beans share a `traceId` and carry an increasing `depth`, verified across chains of 2, 3, 5, and 7 levels.
- `Slf4jInvocationEventEmitter`: default SLF4J-backed log emitter, concise by default, appending trace/depth detail only for nested calls.
- `MicrometerMetricsRecorder`: `method.invocations`, `method.duration`, and `method.errors` meters with low-cardinality tags, always recorded regardless of sampling.
- `SpringSecurityClientInfoAdapter`: resolves the caller as the authenticated Spring Security principal, falling back to the request's remote address (never trusting `X-Forwarded-For` unless explicitly opted into via configuration).
- `LoggedTargetGuardBeanPostProcessor`: fails application startup if `@Logged` is applied to a JPA entity, a `@Repository` bean, or a Spring Data repository implementation.
- `LoggedAutoConfiguration`: full Spring Boot auto-configuration wiring all of the above, with `@ConditionalOnMissingBean` back-off for every collaborator and `@ConditionalOnClass`/`@ConditionalOnBean` fallbacks to no-op implementations when Spring Security, Spring Web, or Micrometer are absent.
- `LoggedProperties` (`logged.*`): `enabled`, `metrics.enabled`, and `client-info.trust-forwarded-headers`, each defaulting to today's behavior unmodified.
- `logged-benchmarks`: internal JMH benchmarks measuring the aspect's overhead against a direct, uninstrumented call.
- Quality gates: Pitest mutation testing (`logged-core` 100%, `logged-spring` 98%) and Checkstyle (0 violations), both enforced via `mvn verify`.

[Unreleased]: https://github.com/fayupable/logged-lib/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/fayupable/logged-lib/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/fayupable/logged-lib/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/fayupable/logged-lib/releases/tag/v1.0.0
