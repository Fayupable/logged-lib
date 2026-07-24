package com.fayupable.logged.benchmarks;

import com.fayupable.logged.core.annotation.Logged;
import com.fayupable.logged.core.port.NoOpClientInfoPort;
import com.fayupable.logged.core.port.NoOpInvocationEventEmitter;
import com.fayupable.logged.core.port.NoOpMetricsRecorder;
import com.fayupable.logged.spring.aspect.LoggedAspect;
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
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.concurrent.TimeUnit;

/**
 * Measures the overhead {@link LoggedAspect} adds to a method call,
 * compared to calling the same logic directly with no interception at all.
 *
 * <p>All ports are wired to their no-op implementations, so this measures
 * the cost of the proxy dispatch, the {@link com.fayupable.logged.core.model.FlowContext}
 * ThreadLocal management, and the metrics/emission decision path itself,
 * isolated from any actual logging or metrics backend I/O.
 *
 * <p>Run with: {@code mvn -pl logged-benchmarks package} then
 * {@code java -jar logged-benchmarks/target/benchmarks.jar}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class LoggedAspectBenchmark {

    private PlainService plainService;
    private LoggedService loggedService;

    @Setup
    public void setUp() {
        plainService = new PlainServiceImpl();

        LoggedAspect aspect = new LoggedAspect(
                new NoOpInvocationEventEmitter(),
                new NoOpMetricsRecorder(),
                new NoOpClientInfoPort()
        );
        AspectJProxyFactory factory = new AspectJProxyFactory(new LoggedServiceImpl());
        factory.addAspect(aspect);
        loggedService = factory.getProxy();
    }

    @Benchmark
    public int baseline() {
        return plainService.compute(21);
    }

    @Benchmark
    public int logged() {
        return loggedService.compute(21);
    }

    interface PlainService {
        int compute(int input);
    }

    static class PlainServiceImpl implements PlainService {
        @Override
        public int compute(int input) {
            return input * 2;
        }
    }

    interface LoggedService {
        int compute(int input);
    }

    static class LoggedServiceImpl implements LoggedService {
        @Logged(sampleRate = 0.0)
        @Override
        public int compute(int input) {
            return input * 2;
        }
    }
}
