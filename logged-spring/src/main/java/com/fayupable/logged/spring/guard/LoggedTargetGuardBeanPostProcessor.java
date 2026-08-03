package com.fayupable.logged.spring.guard;

import com.fayupable.logged.core.annotation.Logged;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Fails application startup when {@link Logged} is applied to a method that
 * cannot be correctly instrumented: a method on a JPA entity, a Spring Data
 * repository interface implementation, a class annotated with
 * {@code @Repository}, or a method returning a reactive
 * {@code org.reactivestreams.Publisher} (Reactor's {@code Mono}/{@code Flux},
 * or an RxJava adapter that implements the same interface).
 *
 * <p>Instrumenting an entity or repository is almost always a mistake.
 * Entities are plain data holders whose getters can be invoked extremely
 * frequently (for example during JSON serialization or JPA dirty checking),
 * turning a single request into thousands of log lines and metric updates.
 * Repositories are already covered by lower-level persistence metrics
 * (query timing, connection pool statistics); wrapping every repository
 * call in the same interceptor as business logic tends to blur, rather
 * than clarify, what is slow.
 *
 * <p>A method returning a reactive {@code Publisher} cannot be correctly
 * instrumented by this library today for a different reason: {@code @Logged}
 * only defers its duration/outcome recording for {@link
 * java.util.concurrent.CompletableFuture}, via {@code whenComplete}. A
 * reactive type built with Project Reactor or RxJava is lazy — nothing runs
 * until something subscribes — so {@code ProceedingJoinPoint#proceed()}
 * returning only means the pipeline was assembled, not that it ran. Silently
 * recording at that point would report a near-zero duration and
 * unconditional success regardless of what the reactive pipeline actually
 * does once subscribed to, which is worse than not measuring it at all: it
 * looks like real data. Rejecting it at startup is preferred over recording
 * something misleading; full reactive support (bridging {@code FlowContext}
 * and MDC through Reactor's {@code Context}) is a substantially larger
 * feature, not yet implemented.
 *
 * <p>This check runs once per bean, before Spring's AOP auto-proxy creator
 * wraps the bean, so it inspects the original target class rather than a
 * proxy. It intentionally does not import {@code jakarta.persistence.Entity},
 * {@code org.springframework.stereotype.Repository},
 * {@code org.springframework.data.repository.Repository}, or
 * {@code org.reactivestreams.Publisher} directly, and instead compares fully
 * qualified names through reflection. This keeps this module free of a hard
 * dependency on JPA, Spring Data, or a reactive streams implementation, so
 * consumers who use none of these are not forced to bring them onto the
 * classpath.
 */
public class LoggedTargetGuardBeanPostProcessor implements BeanPostProcessor {

    private static final String JPA_ENTITY_ANNOTATION = "jakarta.persistence.Entity";
    private static final String SPRING_REPOSITORY_ANNOTATION = "org.springframework.stereotype.Repository";
    private static final String SPRING_DATA_REPOSITORY_INTERFACE = "org.springframework.data.repository.Repository";
    private static final String REACTIVE_PUBLISHER_INTERFACE = "org.reactivestreams.Publisher";

    @Override
    public Object postProcessBeforeInitialization(Object bean, @NonNull String beanName) throws BeansException {
        Class<?> targetClass = bean.getClass();
        List<Method> loggedMethods = findLoggedMethods(targetClass);

        if (!loggedMethods.isEmpty() && isDisallowedTarget(targetClass)) {
            throw new IllegalStateException(
                    "@Logged is not allowed on class " + targetClass.getName() + " (bean '" + beanName + "'): "
                            + "entities, @Repository beans, and Spring Data repository implementations must not "
                            + "carry method-level observability instrumentation. Move @Logged to the service or "
                            + "application layer method that calls this bean instead."
            );
        }

        for (Method method : loggedMethods) {
            if (implementsInterfaceNamed(method.getReturnType(), REACTIVE_PUBLISHER_INTERFACE)) {
                throw new IllegalStateException(
                        "@Logged is not supported on method " + targetClass.getName() + "#" + method.getName()
                                + " (bean '" + beanName + "'): it returns " + method.getReturnType().getName()
                                + ", a reactive publisher. Recording would measure only how long the pipeline took "
                                + "to assemble, not the actual asynchronous work, and would always report success "
                                + "even if the pipeline later fails. Use CompletableFuture instead, or remove "
                                + "@Logged from this method until reactive support is added."
                );
            }
        }

        return bean;
    }

    /**
     * Collects every {@code @Logged}-annotated method declared anywhere in
     * {@code targetClass}'s superclass chain.
     *
     * <p>Walks the superclass chain rather than checking only
     * {@code targetClass.getDeclaredMethods()}, since {@code @Logged} placed
     * on an abstract base class method (inherited, not redeclared, by a
     * disallowed subclass such as a {@code @Repository} or JPA entity) would
     * otherwise bypass this guard entirely, even though Spring AOP's own
     * {@code @annotation(logged)} pointcut still intercepts that inherited
     * method at runtime.
     *
     * @param targetClass the bean's class to inspect
     * @return every {@code @Logged} method found; empty if none
     */
    private List<Method> findLoggedMethods(Class<?> targetClass) {
        List<Method> loggedMethods = new ArrayList<>();
        for (Class<?> current = targetClass; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Logged.class)) {
                    loggedMethods.add(method);
                }
            }
        }
        return loggedMethods;
    }

    private boolean isDisallowedTarget(Class<?> targetClass) {
        return hasAnnotationNamed(targetClass, JPA_ENTITY_ANNOTATION)
                || hasAnnotationNamed(targetClass, SPRING_REPOSITORY_ANNOTATION)
                || implementsInterfaceNamed(targetClass, SPRING_DATA_REPOSITORY_INTERFACE);
    }

    private boolean hasAnnotationNamed(Class<?> targetClass, String annotationName) {
        for (Annotation annotation : targetClass.getAnnotations()) {
            if (annotation.annotationType().getName().equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    private boolean implementsInterfaceNamed(Class<?> type, String interfaceName) {
        if (type == null || type == Object.class) {
            return false;
        }
        for (Class<?> implementedInterface : type.getInterfaces()) {
            if (implementedInterface.getName().equals(interfaceName)
                    || implementsInterfaceNamed(implementedInterface, interfaceName)) {
                return true;
            }
        }
        return implementsInterfaceNamed(type.getSuperclass(), interfaceName);
    }
}
