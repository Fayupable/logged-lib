package com.fayupable.logged.benchmarks;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fayupable.logged.core.annotation.Logged;
import com.fayupable.logged.core.model.MethodInvocationEvent;
import com.fayupable.logged.core.port.IClientInfoPort;
import com.fayupable.logged.core.port.NoOpInvocationEventEmitter;
import com.fayupable.logged.core.port.NoOpMetricsRecorder;
import com.fayupable.logged.spring.aspect.LoggedAspect;
import com.fayupable.logged.spring.emitter.Slf4jInvocationEventEmitter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Measures the paths that write caller information as text: the SLF4J
 * summary line and the MDC entries pushed while a {@code @Logged} method
 * with {@code includeIp}/{@code includeDevice} runs.
 *
 * <p>Logback is set to INFO with every appender detached, so the logging
 * call itself still runs but nothing is written anywhere; the numbers
 * reflect CPU cost only, not console or file I/O.
 *
 * <p>Run with: {@code mvn -pl logged-benchmarks package} then
 * {@code java -jar logged-benchmarks/target/benchmarks.jar CallerInfoOutputBenchmark}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class CallerInfoOutputBenchmark {

    private Slf4jInvocationEventEmitter emitter;
    private MethodInvocationEvent event;
    private SecuredService securedService;

    @Setup
    public void setUp() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.detachAndStopAllAppenders();
        root.setLevel(Level.INFO);

        emitter = new Slf4jInvocationEventEmitter();
        event = new MethodInvocationEvent("AuthService", "login", Instant.now(), 87_000_000L, true, null, null,
                "user:j***@example.com", "203.0.113.5", "Windows / Chrome 129", "fd6a56d153407322", 0);

        LoggedAspect aspect = new LoggedAspect(new NoOpInvocationEventEmitter(), new NoOpMetricsRecorder(),
                new FixedClientInfoPort());
        AspectJProxyFactory factory = new AspectJProxyFactory(new SecuredServiceImpl());
        factory.addAspect(aspect);
        securedService = factory.getProxy();
    }

    @Benchmark
    public void slf4jSummaryLine() {
        emitter.emit(event);
    }

    @Benchmark
    public int loggedWithIpAndDevice() {
        return securedService.compute(21);
    }

    interface SecuredService {
        int compute(int input);
    }

    static class SecuredServiceImpl implements SecuredService {
        @Logged(includeIp = true, includeDevice = true, sampleRate = 0.0)
        @Override
        public int compute(int input) {
            return input * 2;
        }
    }

    static class FixedClientInfoPort implements IClientInfoPort {
        @Override
        public String resolveCallerIdentity() {
            return "user:j***@example.com";
        }

        @Override
        public String resolveCallerIp() {
            return "203.0.113.5";
        }

        @Override
        public String resolveCallerDevice() {
            return "Windows / Chrome 129";
        }
    }
}
