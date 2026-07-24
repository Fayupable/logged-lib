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
- [Where `@Logged` belongs](#where-logged-belongs)
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

## Configuration

Every property below is optional and defaults to preserving the library's out-of-the-box behavior unmodified.

| Property | Default | Effect |
|---|---|---|
| `logged.enabled` | `true` | Global switch. Setting this to `false` removes the aspect entirely; `@Logged` methods run uninstrumented. |
| `logged.metrics.enabled` | `true` | Setting this to `false` falls back to a no-op metrics recorder even when a `MeterRegistry` bean is present. |
| `logged.client-info.trust-forwarded-headers` | `false` | Whether the caller-IP resolver may trust the client-controlled `X-Forwarded-For` header. Only enable this after confirming the application sits behind a proxy that strips and re-sets this header itself — see [Caller resolution](#caller-resolution). |

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
| `logged-benchmarks` | `logged-core`, `logged-spring` | ❌ internal only |

- **`logged-core`** is framework-free: the `@Logged` annotation, the `MethodInvocationEvent`/`FlowContext` models, and the output ports (`InvocationEventEmitter`, `MetricsRecorder`, `IClientInfoPort`), each with a no-op default. It has zero dependencies and can be used standalone by any interception mechanism — not only Spring AOP.
- **`logged-spring`** is the Spring Boot adapter: the `@Around` aspect, an SLF4J-backed emitter, a Micrometer-backed metrics recorder, a Spring Security-backed caller resolver, the startup guard described above, and full auto-configuration. Every Spring/Micrometer/Security dependency it declares is `provided` scope, so none of it is forced onto a consuming project's dependency tree — if your application doesn't already have Micrometer or Spring Security, the corresponding feature simply falls back to a no-op.
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

- **Tests**: 77 across both modules, `mvn test`.
- **Mutation testing** ([Pitest](https://pitest.org/)): `logged-core` at 100%, `logged-spring` at 98%, enforced via `mvn verify`.
- **Style** ([Checkstyle](https://checkstyle.org/)): 0 violations, a small rule set (unused/star imports, missing braces, unreachable line lengths) chosen to catch real mistakes without dictating subjective formatting.

```bash
mvn verify
```

runs tests, mutation testing, and style checks together.

## License

[Apache License 2.0](LICENSE).
