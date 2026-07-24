package com.fayupable.logged.spring.guard;

import com.fayupable.logged.core.annotation.Logged;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

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

    interface CustomRepository extends org.springframework.data.repository.Repository<Object, Long> {
    }

    static class CustomRepositoryImpl implements CustomRepository {
        @Logged
        public void findAll() {
        }
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
}
