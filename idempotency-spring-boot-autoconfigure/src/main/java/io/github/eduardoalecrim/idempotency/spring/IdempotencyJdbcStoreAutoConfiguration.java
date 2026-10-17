// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring;

import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStoreException;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyProperties.InitializeSchema;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyProperties.JdbcDialectName;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyProperties.StoreType;
import io.github.eduardoalecrim.idempotency.store.jdbc.JdbcDialect;
import io.github.eduardoalecrim.idempotency.store.jdbc.JdbcIdempotencyStore;
import io.github.eduardoalecrim.idempotency.store.jdbc.JdbcSchema;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The JDBC store on the application's {@link DataSource}: with {@code idempotency.store.type=auto} when there is a
 * single data source, and always with {@code jdbc}. Creates the table when {@code initialize-schema} asks for it, then
 * validates it when {@code validate-schema} is on (FR-25).
 */
@AutoConfiguration(
        before = IdempotencyInMemoryStoreAutoConfiguration.class,
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass({JdbcIdempotencyStore.class, DataSource.class, EmbeddedDatabaseConnection.class})
@ConditionalOnBooleanProperty(name = "idempotency.enabled", matchIfMissing = true)
@ConditionalOnMissingBean(IdempotencyStore.class)
public final class IdempotencyJdbcStoreAutoConfiguration {

    private static final String PREFIX = "idempotency.store.jdbc.";

    /** With {@code idempotency.store.type=auto}, the store needs exactly one data source. */
    @Configuration(proxyBeanMethods = false)
    @Conditional(OnStoreType.Auto.class)
    @ConditionalOnSingleCandidate(DataSource.class)
    static class Auto {

        @Bean
        JdbcIdempotencyStore idempotencyStore(DataSource dataSource, IdempotencyProperties properties) {
            return store(dataSource, properties);
        }
    }

    /** With {@code idempotency.store.type=jdbc}, a missing data source fails startup. */
    @Configuration(proxyBeanMethods = false)
    @Conditional(OnStoreType.Jdbc.class)
    static class Explicit {

        @Bean
        JdbcIdempotencyStore idempotencyStore(
                ObjectProvider<DataSource> dataSources, IdempotencyProperties properties) {
            DataSource dataSource = dataSources.getIfUnique();
            if (dataSource == null) {
                throw new IdempotencyConfigurationException(
                        "idempotency.store.type is jdbc, but there is no single DataSource bean",
                        "Configure one DataSource (spring.datasource.*), or mark one @Primary.");
            }
            return store(dataSource, properties);
        }
    }

    static JdbcIdempotencyStore store(DataSource dataSource, IdempotencyProperties properties) {
        IdempotencyProperties.Store settings = properties.getStore();
        IdempotencyProperties.Store.Jdbc jdbc = settings.getJdbc();
        Duration timeout = settings.getTimeout();
        if (timeout.isNegative() || timeout.isZero()) {
            throw IdempotencyConfigurationException.property("idempotency.store.timeout", "it must be positive");
        }
        JdbcIdempotencyStore.Builder builder =
                JdbcIdempotencyStore.builder(dataSource).queryTimeout(timeout);
        try {
            builder.tableName(jdbc.getTableName());
        } catch (IllegalArgumentException e) {
            throw IdempotencyConfigurationException.property(
                    PREFIX + "table-name", "it must match [A-Za-z_][A-Za-z0-9_.]*");
        }
        if (jdbc.getDialect() != JdbcDialectName.AUTO) {
            builder.dialect(JdbcDialect.valueOf(jdbc.getDialect().name()));
        }
        JdbcIdempotencyStore store = builder.build();
        if (createsSchema(jdbc.getInitializeSchema(), dataSource)) {
            if (store.tableName().contains(".")) {
                throw IdempotencyConfigurationException.property(
                        PREFIX + "initialize-schema", "the table can be created only under an unqualified name");
            }
            JdbcSchema.createIfMissing(dataSource, dialect(store), store.tableName());
        }
        if (jdbc.isValidateSchema()) {
            validate(dataSource, store);
        }
        return store;
    }

    private static boolean createsSchema(InitializeSchema initialize, DataSource dataSource) {
        return switch (initialize) {
            case NEVER -> false;
            case EMBEDDED -> EmbeddedDatabaseConnection.isEmbedded(dataSource);
            case ALWAYS -> true;
        };
    }

    private static void validate(DataSource dataSource, JdbcIdempotencyStore store) {
        List<String> problems;
        try {
            problems = JdbcSchema.problems(dataSource, store.tableName());
        } catch (IdempotencyStoreException e) {
            throw new IdempotencyConfigurationException(
                    "The idempotency table could not be validated: " + e.getMessage(),
                    "Check the DataSource, or set " + PREFIX + "validate-schema=false.");
        }
        if (!problems.isEmpty()) {
            String script = JdbcSchema.scriptPath(dialect(store));
            throw new IdempotencyConfigurationException(
                    "The idempotency table is not ready: " + String.join(", ", problems) + ".",
                    "Create it with " + script + " from idempotency-store-jdbc, include db/idempotency/changelog.yaml"
                            + " in your Liquibase changelog, or set " + PREFIX + "initialize-schema=always.");
        }
    }

    private static JdbcDialect dialect(JdbcIdempotencyStore store) {
        try {
            return store.dialect();
        } catch (IdempotencyStoreException e) {
            throw new IdempotencyConfigurationException(
                    e.getMessage() == null ? "The database dialect could not be detected" : e.getMessage(),
                    "Set " + PREFIX + "dialect to postgresql, mysql, mariadb, oracle or sqlserver.");
        }
    }

    /** Matches the value of {@code idempotency.store.type}. */
    abstract static class OnStoreType extends SpringBootCondition {

        private final StoreType expected;

        OnStoreType(StoreType expected) {
            this.expected = expected;
        }

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            StoreType type = Binder.get(context.getEnvironment())
                    .bind("idempotency.store.type", StoreType.class)
                    .orElse(StoreType.AUTO);
            return type == expected
                    ? ConditionOutcome.match("idempotency.store.type is " + type)
                    : ConditionOutcome.noMatch("idempotency.store.type is " + type);
        }

        static final class Auto extends OnStoreType {
            Auto() {
                super(StoreType.AUTO);
            }
        }

        static final class Jdbc extends OnStoreType {
            Jdbc() {
                super(StoreType.JDBC);
            }
        }
    }
}
