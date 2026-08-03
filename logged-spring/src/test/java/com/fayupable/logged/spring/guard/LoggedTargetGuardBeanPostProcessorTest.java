package com.fayupable.logged.spring.guard;

import com.fayupable.logged.core.annotation.Logged;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("LoggedTargetGuardBeanPostProcessor")
class LoggedTargetGuardBeanPostProcessorTest {

    private final LoggedTargetGuardBeanPostProcessor guard = new LoggedTargetGuardBeanPostProcessor();

    @Nested
    @DisplayName("on a JPA entity")
    class OnJpaEntity {

        @Test
        @DisplayName("rejects a @Logged method")
        void rejectsLoggedMethod() {
            assertThatThrownBy(() -> guard.postProcessBeforeInitialization(new AnnotatedEntity(), "annotatedEntity"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AnnotatedEntity")
                    .hasMessageContaining("@Logged");
        }

        @Test
        @DisplayName("allows an entity with no @Logged methods")
        void allowsEntityWithoutLoggedMethods() {
            assertThatCode(() -> guard.postProcessBeforeInitialization(new PlainEntity(), "plainEntity"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("on a @Repository bean")
    class OnRepositoryAnnotatedBean {

        @Test
        @DisplayName("rejects a @Logged method")
        void rejectsLoggedMethod() {
            assertThatThrownBy(() -> guard.postProcessBeforeInitialization(new AnnotatedRepositoryBean(), "annotatedRepositoryBean"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AnnotatedRepositoryBean");
        }

        @Test
        @DisplayName("rejects a @Logged method inherited from an abstract base class, not just one declared directly")
        void rejectsInheritedLoggedMethod() {
            assertThatThrownBy(() -> guard.postProcessBeforeInitialization(new RepositoryWithInheritedLoggedMethod(), "repositoryWithInheritedLoggedMethod"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RepositoryWithInheritedLoggedMethod");
        }
    }

    @Nested
    @DisplayName("on a Spring Data repository implementation")
    class OnSpringDataRepositoryImplementation {

        @Test
        @DisplayName("rejects a @Logged method")
        void rejectsLoggedMethod() {
            assertThatThrownBy(() -> guard.postProcessBeforeInitialization(new CustomRepositoryImpl(), "customRepositoryImpl"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CustomRepositoryImpl");
        }

        @Test
        @DisplayName("rejects a bean whose superclass, not the bean's own declared interfaces, implements the Spring Data repository interface")
        void rejectsWhenInterfaceIsFoundViaSuperclassChain() {
            assertThatThrownBy(() -> guard.postProcessBeforeInitialization(new DerivedCustomRepositoryImpl(), "derivedCustomRepositoryImpl"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DerivedCustomRepositoryImpl");
        }
    }

    @Nested
    @DisplayName("on an ordinary service class")
    class OnOrdinaryService {

        @Test
        @DisplayName("allows a @Logged method")
        void allowsLoggedMethod() {
            assertThatCode(() -> guard.postProcessBeforeInitialization(new PlainService(), "plainService"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("returns the exact same bean instance unchanged")
        void returnsTheSameBeanInstance() {
            PlainService bean = new PlainService();

            Object result = guard.postProcessBeforeInitialization(bean, "plainService");

            assertThat(result).isSameAs(bean);
        }
    }

    @Nested
    @DisplayName("on deeper class and interface hierarchies unrelated to Spring Data")
    class OnDeeperHierarchies {

        @Test
        @DisplayName("allows a class implementing an unrelated multi-level interface hierarchy")
        void allowsUnrelatedInterfaceHierarchy() {
            assertThatCode(() -> guard.postProcessBeforeInitialization(new DeepHierarchyService(), "deepHierarchyService"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("allows a class with a non-trivial superclass chain and no interfaces")
        void allowsNonTrivialSuperclassChain() {
            assertThatCode(() -> guard.postProcessBeforeInitialization(new DerivedService(), "derivedService"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("on a method returning a reactive Publisher")
    class OnReactivePublisherReturnType {

        @Test
        @DisplayName("rejects a @Logged method returning Mono")
        void rejectsMonoReturnType() {
            assertThatThrownBy(() -> guard.postProcessBeforeInitialization(new MonoReturningService(), "monoReturningService"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MonoReturningService")
                    .hasMessageContaining("reactive publisher");
        }

        @Test
        @DisplayName("rejects a @Logged method returning Flux")
        void rejectsFluxReturnType() {
            assertThatThrownBy(() -> guard.postProcessBeforeInitialization(new FluxReturningService(), "fluxReturningService"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FluxReturningService")
                    .hasMessageContaining("reactive publisher");
        }

        @Test
        @DisplayName("allows a @Logged method returning CompletableFuture")
        void allowsCompletableFutureReturnType() {
            assertThatCode(() -> guard.postProcessBeforeInitialization(new CompletableFutureReturningService(), "completableFutureReturningService"))
                    .doesNotThrowAnyException();
        }
    }

    @Entity
    static class AnnotatedEntity {
        @Logged
        public void save() {
        }
    }

    @Entity
    static class PlainEntity {
        public void save() {
        }
    }

    @Repository
    static class AnnotatedRepositoryBean {
        @Logged
        public void findAll() {
        }
    }

    static class BaseRepositoryWithLoggedMethod {
        @Logged
        public void save() {
        }
    }

    @Repository
    static class RepositoryWithInheritedLoggedMethod extends BaseRepositoryWithLoggedMethod {
    }

    interface CustomRepository extends org.springframework.data.repository.Repository<Object, Long> {
    }

    static class CustomRepositoryImpl implements CustomRepository {
        @Logged
        public void findAll() {
        }
    }

    static class DerivedCustomRepositoryImpl extends CustomRepositoryImpl {
    }

    @Service
    static class PlainService {
        @Logged
        public void doWork() {
        }
    }

    interface UnrelatedParentInterface {
    }

    interface UnrelatedChildInterface extends UnrelatedParentInterface {
    }

    static class DeepHierarchyService implements UnrelatedChildInterface {
        @Logged
        public void run() {
        }
    }

    static class BaseService {
    }

    static class DerivedService extends BaseService {
        @Logged
        public void run() {
        }
    }

    static class MonoReturningService {
        @Logged
        public Mono<String> getValue() {
            return Mono.just("value");
        }
    }

    static class FluxReturningService {
        @Logged
        public Flux<String> getValues() {
            return Flux.just("value");
        }
    }

    static class CompletableFutureReturningService {
        @Logged
        public CompletableFuture<String> getValue() {
            return CompletableFuture.completedFuture("value");
        }
    }
}
