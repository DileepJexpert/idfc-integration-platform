package com.idfcfirstbank.integration.orchestration.originationjourney.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the REAL SFDC front-door routing in the local profile. bootRun loads
 * {@code application-local.yml}; {@code full-flow-it} supplies routing as CLI
 * args (its classpath carries many modules' yml, so it can't exercise this one),
 * so this test is the guard that the real Apple svcName — a mixed-case map key —
 * actually binds and routes to the device-validation journey on a real run.
 */
class TypeToJourneyLocalYamlTest {

    @Test
    void appleSvcNameAndTypesRouteToTheirJourneysInTheLocalProfile() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("application-local", new ClassPathResource("application-local.yml"))
                .get(0);
        Binder binder = new Binder(ConfigurationPropertySources.from(source));

        Map<String, String> typeToJourney = binder
                .bind("idfc.engine.type-to-journey", Bindable.mapOf(String.class, String.class))
                .get();

        assertThat(typeToJourney)
                .as("the REAL SFDC front door: svcName Post_Disbursal_Apple -> device-validation")
                .containsEntry("Post_Disbursal_Apple", "device-validation")
                .as("the secondary Kafka door is kept alongside it")
                .containsEntry("DEVICE_VALIDATION", "device-validation");
    }
}
