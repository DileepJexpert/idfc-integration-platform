package com.idfcfirstbank.integration.capabilities.devicevalidation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the REAL bootRun binding of the capability's own {@code application.yml}:
 * the APPLE brand row declares its {@code svc-name} (so brand=APPLE is derived
 * for the SFDC Post_Disbursal_Apple front door, whose payload has no brand field)
 * and the three activity flags + {@code validate-by} bind. full-flow-it feeds
 * every row as a CLI arg (its classpath carries many modules' yml), so this is
 * the guard for the actual yml a real run loads.
 */
class DeviceValidationYamlBindingTest {

    private DeviceValidationProperties bind() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);
        Binder binder = new Binder(ConfigurationPropertySources.from(source));
        return binder.bind("device-validation", Bindable.of(DeviceValidationProperties.class)).get();
    }

    @Test
    void appleRowDeclaresSvcName_flagsAndValidateByBind() throws Exception {
        DeviceValidationProperties props = bind();

        assertThat(props.brands()).containsKeys("APPLE", "SAMSUNG", "GODREJ", "BOSCH");
        assertThat(props.brandForSvcName("Post_Disbursal_Apple"))
                .as("real SFDC svcName -> brand=APPLE, derived from the row's svc-name")
                .isEqualTo("APPLE");

        var apple = props.brands().get("APPLE");
        assertThat(apple.validate()).isFalse();       // post-disbursal = block only
        assertThat(apple.block()).isTrue();
        assertThat(apple.unblock()).isFalse();
        assertThat(apple.validateBy()).isEqualTo("imei");

        var godrej = props.brands().get("GODREJ");
        assertThat(godrej.validateBy()).as("appliance brand identified by serial").isEqualTo("serial");
    }

    @Test
    void statusActivitiesBind_status1IsValidateBlock_status2IsUnblock() throws Exception {
        DeviceValidationProperties props = bind();
        assertThat(props.requestedActivities("1")).containsExactly("validate", "block");
        assertThat(props.requestedActivities("2")).containsExactly("unblock");
        assertThat(props.requestedActivities(null))
                .as("absent status -> default-status '1'")
                .containsExactly("validate", "block");
    }
}
