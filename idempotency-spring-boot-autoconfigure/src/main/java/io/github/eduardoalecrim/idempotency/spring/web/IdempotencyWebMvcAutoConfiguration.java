// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import io.github.eduardoalecrim.idempotency.ClientIdentityResolver;
import io.github.eduardoalecrim.idempotency.IdempotencyEngine;
import io.github.eduardoalecrim.idempotency.IdempotencyPolicy;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyAutoConfiguration;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyProperties;
import io.github.eduardoalecrim.idempotency.spring.PropertiesMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** The Spring MVC adapter, in servlet web applications. */
@AutoConfiguration(after = IdempotencyAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, FilterRegistrationBean.class})
@ConditionalOnBean(IdempotencyEngine.class)
public final class IdempotencyWebMvcAutoConfiguration {

    /** Finds the operation of each request; the handler mapping is looked up on first use. */
    @Bean
    @ConditionalOnMissingBean
    IdempotentHandlerResolver idempotentHandlerResolver(
            IdempotencyProperties properties,
            @Qualifier(IdempotencyAutoConfiguration.DEFAULT_POLICY_BEAN) IdempotencyPolicy defaults,
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mapping,
            BeanFactory beanFactory) {
        return new IdempotentHandlerResolver(
                mapping::getObject, new OperationPolicies(defaults, beanFactory), properties.getRoutes());
    }

    /** The filter, at {@code idempotency.web.filter-order}, for request dispatches only. */
    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(
            IdempotencyEngine engine,
            IdempotentHandlerResolver resolver,
            IdempotencyProperties properties,
            ObjectProvider<ClientIdentityResolver<HttpServletRequest>> identity) {
        IdempotencyFilter filter = new IdempotencyFilter(
                engine,
                resolver,
                PropertiesMapper.methods(properties),
                identity.getIfUnique(() -> ServletClientIdentity.from(properties.getClientIdentity())));
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("idempotencyFilter");
        registration.setOrder(properties.getWeb().getFilterOrder());
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }
}
