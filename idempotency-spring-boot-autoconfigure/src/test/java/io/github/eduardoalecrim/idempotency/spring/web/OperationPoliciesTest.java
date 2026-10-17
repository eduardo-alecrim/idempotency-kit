// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eduardoalecrim.idempotency.IdempotencyPolicy;
import io.github.eduardoalecrim.idempotency.Idempotent;
import io.github.eduardoalecrim.idempotency.fingerprint.FingerprintInput;
import io.github.eduardoalecrim.idempotency.fingerprint.FingerprintStrategy;
import io.github.eduardoalecrim.idempotency.spi.Fingerprint;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyConfigurationException;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyProperties;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class OperationPoliciesTest {

    private final IdempotencyPolicy defaults =
            IdempotencyPolicy.builder().fingerprintHeaders(List.of("x-tenant")).build();
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final OperationPolicies policies = new OperationPolicies(defaults, beans);

    @Test
    void appliesTheAnnotationOverDefaults() throws Exception {
        IdempotencyPolicy policy = policies.forAnnotation(annotation("custom"), "Handlers#custom");

        assertThat(policy.required()).isFalse();
        assertThat(policy.ttl()).isEqualTo(Duration.ofHours(1));
        assertThat(policy.lease()).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.scope()).isEqualTo(Idempotent.Scope.GLOBAL);
        assertThat(policy.fingerprintHeaders()).containsExactly("x-tenant", "x-region");
        assertThat(policy.fingerprint()).isInstanceOf(ConstantStrategy.class);
    }

    @Test
    void keepsDefaultsForEmptyAttributes() throws Exception {
        IdempotencyPolicy policy = policies.forAnnotation(annotation("plain"), "Handlers#plain");

        assertThat(policy.ttl()).isEqualTo(defaults.ttl());
        assertThat(policy.lease()).isEqualTo(defaults.lease());
        assertThat(policy.fingerprint()).isSameAs(defaults.fingerprint());
    }

    @Test
    @DisplayName("FR-15: a fingerprint strategy bean is used instead of a new instance")
    void prefersStrategyBeans() throws Exception {
        ConstantStrategy bean = new ConstantStrategy();
        beans.registerSingleton("constantStrategy", bean);

        assertThat(policies.forAnnotation(annotation("custom"), "Handlers#custom")
                        .fingerprint())
                .isSameAs(bean);
    }

    @Test
    void namesTheHandlerInErrors() throws Exception {
        assertThatThrownBy(() -> policies.forAnnotation(annotation("badTtl"), "Handlers#badTtl"))
                .isInstanceOf(IdempotencyConfigurationException.class)
                .hasMessageStartingWith("Invalid ttl of @Idempotent on Handlers#badTtl: Invalid duration '1w'");
        assertThatThrownBy(() -> policies.forAnnotation(annotation("longLease"), "Handlers#longLease"))
                .isInstanceOf(IdempotencyConfigurationException.class)
                .hasMessageContaining("@Idempotent on Handlers#longLease")
                .hasMessageContaining("must be shorter than ttl");
        assertThatThrownBy(() -> policies.forAnnotation(annotation("noConstructor"), "Handlers#noConstructor"))
                .isInstanceOf(IdempotencyConfigurationException.class)
                .hasMessageContaining("Cannot create fingerprint strategy");
    }

    @Test
    void buildsConfiguredRoutes() {
        IdempotencyProperties.Route route = new IdempotencyProperties.Route();
        route.setPath("/legacy");
        route.setRequired(false);
        route.setTtl(Duration.ofMinutes(10));

        IdempotencyPolicy policy = policies.forRoute(route, "idempotency.routes[0]");

        assertThat(policy.required()).isFalse();
        assertThat(policy.ttl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.lease()).isEqualTo(defaults.lease());
    }

    @Test
    void resolverRejectsRoutesWithoutAPathAndListsTheOthers() {
        IdempotencyProperties.Route missing = new IdempotencyProperties.Route();
        assertThatThrownBy(() ->
                        new IdempotentHandlerResolver(OperationPoliciesTest::noMapping, policies, List.of(missing)))
                .isInstanceOf(IdempotencyConfigurationException.class)
                .hasMessageContaining("idempotency.routes[0].path");

        IdempotencyProperties.Route route = new IdempotencyProperties.Route();
        route.setPath("/legacy/{id}");
        route.setMethods(List.of("post", "PATCH"));
        assertThat(new IdempotentHandlerResolver(OperationPoliciesTest::noMapping, policies, List.of(route))
                        .configuredRoutes())
                .containsExactlyInAnyOrder("POST /legacy/{id}", "PATCH /legacy/{id}");
    }

    @Test
    void resolverReadsMethodThenClassAnnotations() throws Exception {
        IdempotentHandlerResolver resolver =
                new IdempotentHandlerResolver(OperationPoliciesTest::noMapping, policies, List.of());
        Handlers handlers = new Handlers();

        assertThat(resolver.policyOf(new HandlerMethod(handlers, Handlers.class.getDeclaredMethod("custom"))))
                .hasValueSatisfying(policy -> assertThat(policy.required()).isFalse());
        assertThat(resolver.policyOf(new HandlerMethod(new Annotated(), Annotated.class.getDeclaredMethod("run"))))
                .hasValueSatisfying(policy -> assertThat(policy.required()).isTrue());
        assertThat(resolver.policyOf(new HandlerMethod(handlers, Handlers.class.getDeclaredMethod("none"))))
                .isEmpty();
        assertThat(IdempotentHandlerResolver.describe(Handlers.class, Handlers.class.getDeclaredMethod("none")))
                .isEqualTo("Handlers#none");
    }

    private static Idempotent annotation(String method) throws NoSuchMethodException {
        return Objects.requireNonNull(Handlers.class.getDeclaredMethod(method).getAnnotation(Idempotent.class));
    }

    private static RequestMappingHandlerMapping noMapping() {
        throw new IllegalStateException("not used");
    }

    static class Handlers {

        @Idempotent(
                required = false,
                ttl = "1h",
                lease = "5s",
                scope = Idempotent.Scope.GLOBAL,
                fingerprint = ConstantStrategy.class,
                fingerprintHeaders = "X-Region")
        void custom() {}

        @Idempotent
        void plain() {}

        @Idempotent(ttl = "1w")
        void badTtl() {}

        @Idempotent(ttl = "10s", lease = "10s")
        void longLease() {}

        @Idempotent(fingerprint = NoConstructorStrategy.class)
        void noConstructor() {}

        void none() {}
    }

    @Idempotent
    static class Annotated {
        void run() {}
    }

    public static class ConstantStrategy implements FingerprintStrategy {
        @Override
        public Fingerprint fingerprint(FingerprintInput input) {
            return new Fingerprint("0".repeat(64));
        }
    }

    public static class NoConstructorStrategy implements FingerprintStrategy {
        public NoConstructorStrategy(String unused) {}

        @Override
        public Fingerprint fingerprint(FingerprintInput input) {
            return new Fingerprint("1".repeat(64));
        }
    }
}
