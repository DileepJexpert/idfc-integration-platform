package com.idfcfirstbank.integration.capabilities.devicefinancing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the REAL bootRun binding of the capability's own {@code application.yml}:
 * the APPLE brand row must declare its {@code svc-name} so the capability derives
 * brand=APPLE for the SFDC Post_Disbursal_Apple front door (whose payload has no
 * brand field). full-flow-it feeds every row as a CLI arg (its classpath carries
 * many modules' yml), so this is the guard for the actual yml a real run loads.
 */
class DeviceFinancingYamlBindingTest {

    @Test
    void appleRowDeclaresItsSvcName_soBrandIsDerivedOnRealBootRun() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);
        Binder binder = new Binder(ConfigurationPropertySources.from(source));

        DeviceFinancingProperties props = binder
                .bind("device-financing", Bindable.of(DeviceFinancingProperties.class))
                .get();

        assertThat(props.brands()).containsKey("APPLE");
        assertThat(props.brandForSvcName("Post_Disbursal_Apple"))
                .as("real SFDC svcName -> brand=APPLE, derived from the row's svc-name")
                .isEqualTo("APPLE");
    }
}
