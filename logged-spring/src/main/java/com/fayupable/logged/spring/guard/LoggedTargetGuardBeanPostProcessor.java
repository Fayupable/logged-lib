package com.fayupable.logged.spring.guard;

import com.fayupable.logged.core.annotation.Logged;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.annotation.Annotation;
import java.util.Arrays;

/**
 * Fails application startup when {@link Logged} is applied to a method on a
 * class that should never carry observability instrumentation: a JPA
 * entity, a Spring Data repository interface implementation, or a class
 * annotated with {@code @Repository}.
 *
 * <p>Instrumenting these layers is almost always a mistake. Entities are
 * plain data holders whose getters can be invoked extremely frequently
 * (for example during JSON serialization or JPA dirty checking), turning a
 * single request into thousands of log lines and metric updates.
 * Repositories are already covered by lower-level persistence metrics
 * (query timing, connection pool statistics); wrapping every repository
 * call in the same interceptor as business logic tends to blur, rather
 * than clarify, what is slow.
 *
 * <p>This check runs once per bean, before Spring's AOP auto-proxy creator
 * wraps the bean, so it inspects the original target class rather than a
 * proxy. It intentionally does not import {@code jakarta.persistence.Entity},
 * {@code org.springframework.stereotype.Repository}, or
 * {@code org.springframework.data.repository.Repository} directly, and
 * instead compares fully qualified names through reflection. This keeps
 * this module free of a hard dependency on JPA or Spring Data, so consumers
 * who use neither are not forced to bring them onto the classpath.
 */
public class LoggedTargetGuardBeanPostProcessor implements BeanPostProcessor {

    private static final String JPA_ENTITY_ANNOTATION = "jakarta.persistence.Entity";
    private static final String SPRING_REPOSITORY_ANNOTATION = "org.springframework.stereotype.Repository";
    private static final String SPRING_DATA_REPOSITORY_INTERFACE = "org.springframework.data.repository.Repository";

    @Override
    public Object postProcessBeforeInitialization(Object bean, @NonNull String beanName) throws BeansException {
        Class<?> targetClass = bean.getClass();

        if (hasLoggedMethod(targetClass) && isDisallowedTarget(targetClass)) {
            throw new IllegalStateException(
                    "@Logged is not allowed on class " + targetClass.getName() + " (bean '" + beanName + "'): "
                            + "entities, @Repository beans, and Spring Data repository implementations must not "
                            + "carry method-level observability instrumentation. Move @Logged to the service or "
                            + "application layer method that calls this bean instead."
            );
        }

        return bean;
    }

    private boolean hasLoggedMethod(Class<?> targetClass) {
        return Arrays.stream(targetClass.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(Logged.class));
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
