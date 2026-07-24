# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - TBD

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

[Unreleased]: https://github.com/fayupable/logged-lib/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/fayupable/logged-lib/releases/tag/v1.0.0
