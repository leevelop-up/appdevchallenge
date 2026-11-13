package com.codrive.server.service;

import com.codrive.server.domain.Feature;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommandValidationTest {
    private static final CapabilityStore.Entry RANGE = new CapabilityStore.Entry(Feature.Status.AVAILABLE, 18.0, 28.0);

    @Test
    void temperatureMustBeInsideVehicleReportedRange() {
        assertThat(CommandService.validate(Feature.HVAC_TEMPERATURE, Map.of("celsius", 22), RANGE)).isNull();
        assertThat(CommandService.validate(Feature.HVAC_TEMPERATURE, Map.of("celsius", 30), RANGE)).isNotNull();
        assertThat(CommandService.validate(Feature.HVAC_TEMPERATURE, Map.of("celsius", "hot"), RANGE)).isNotNull();
    }

    @Test
    void controlWithoutReportedRangeIsRejectedInsteadOfAssumingOne() {
        assertThat(CommandService.validate(Feature.HVAC_TEMPERATURE, Map.of("celsius", 22), null)).isNotNull();
        CapabilityStore.Entry noBounds = new CapabilityStore.Entry(Feature.Status.AVAILABLE, null, null);
        assertThat(CommandService.validate(Feature.HVAC_FAN, Map.of("level", 3), noBounds)).isNotNull();
        assertThat(CommandService.validate(Feature.LIGHT_BRIGHTNESS, Map.of("level", 50), noBounds)).isNotNull();
    }

    @Test
    void destinationNeedsNameAndValidCoordinates() {
        assertThat(CommandService.validate(Feature.NAV_DESTINATION,
                Map.of("name", "서울역", "lat", 37.55, "lng", 126.97), null)).isNull();
        assertThat(CommandService.validate(Feature.NAV_DESTINATION,
                Map.of("name", "", "lat", 37.55, "lng", 126.97), null)).isNotNull();
        assertThat(CommandService.validate(Feature.NAV_DESTINATION,
                Map.of("name", "x", "lat", 91, "lng", 126.97), null)).isNotNull();
    }

    @Test
    void colorMustBeHex() {
        assertThat(CommandService.validate(Feature.LIGHT_COLOR, Map.of("hex", "#00FF88"), null)).isNull();
        assertThat(CommandService.validate(Feature.LIGHT_COLOR, Map.of("hex", "red"), null)).isNotNull();
    }

    @Test
    void musicCommandsAreWhitelisted() {
        for (String c : new String[]{"PLAY", "PAUSE", "NEXT", "PREVIOUS"}) {
            assertThat(CommandService.validate(Feature.MUSIC_CONTROL, Map.of("command", c), null)).isNull();
        }
        assertThat(CommandService.validate(Feature.MUSIC_CONTROL, Map.of("command", "VOLUME_UP"), null)).isNotNull();
        assertThat(CommandService.validate(Feature.MUSIC_CONTROL, Map.of(), null)).isNotNull();
    }

    @Test
    void viewFeaturesAreNotCommands() {
        assertThat(CommandService.validate(Feature.HVAC_VIEW, Map.of(), null)).isNotNull();
    }
}
