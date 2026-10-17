// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.configurationmetadata.ConfigurationMetadataProperty;
import org.springframework.boot.configurationmetadata.ConfigurationMetadataRepositoryJsonBuilder;

class ConfigurationMetadataTest {

    // Properties whose default is "not set" (SPEC §6.3).
    private static final Set<String> WITHOUT_DEFAULT =
            Set.of("idempotency.client-identity.header", "idempotency.fingerprint.headers", "idempotency.routes");

    @Test
    @DisplayName("FR-28: every property has a description and a default")
    void everyPropertyIsDocumented() throws Exception {
        Collection<ConfigurationMetadataProperty> properties;
        try (InputStream in = Objects.requireNonNull(
                getClass().getResourceAsStream("/META-INF/spring-configuration-metadata.json"))) {
            properties = ConfigurationMetadataRepositoryJsonBuilder.create(in)
                    .build()
                    .getAllProperties()
                    .values();
        }

        assertThat(properties).hasSizeGreaterThan(20);
        assertThat(properties).allSatisfy(property -> {
            assertThat(property.getDescription())
                    .as("description of %s", property.getId())
                    .isNotBlank();
            if (!WITHOUT_DEFAULT.contains(property.getId())) {
                assertThat(property.getDefaultValue())
                        .as("default of %s", property.getId())
                        .isNotNull();
            }
        });
    }
}
