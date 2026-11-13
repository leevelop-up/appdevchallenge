package com.codrive.server.service;

import com.codrive.server.domain.Feature;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionPolicyTest {
    private static CapabilityStore.Entry cap(Feature.Status s) {
        return new CapabilityStore.Entry(s, null, null);
    }

    @Test
    void sharedViewFeatureIsAvailableWhenVehicleSupportsIt() {
        assertThat(PermissionPolicy.evaluate(Feature.HVAC_VIEW, Feature.Access.VIEW, cap(Feature.Status.AVAILABLE)))
                .isEqualTo(Feature.Status.AVAILABLE);
    }

    @Test
    void notSharedViewFeatureIsHidden() {
        assertThat(PermissionPolicy.evaluate(Feature.HVAC_VIEW, Feature.Access.NONE, cap(Feature.Status.AVAILABLE)))
                .isNull();
    }

    @Test
    void controlWithoutGrantRequiresPermission() {
        assertThat(PermissionPolicy.evaluate(Feature.HVAC_TEMPERATURE, Feature.Access.NONE, cap(Feature.Status.AVAILABLE)))
                .isEqualTo(Feature.Status.PERMISSION_REQUIRED);
    }

    @Test
    void controlWithGrantIsUsable() {
        Feature.Status s = PermissionPolicy.evaluate(Feature.HVAC_TEMPERATURE, Feature.Access.CONTROL,
                cap(Feature.Status.AVAILABLE));
        assertThat(s).isEqualTo(Feature.Status.AVAILABLE);
        assertThat(PermissionPolicy.usable(Feature.HVAC_TEMPERATURE, s)).isTrue();
    }

    @Test
    void vehicleSideLimitationsWinOverGrant() {
        for (Feature.Status limit : new Feature.Status[]{Feature.Status.NOT_SUPPORTED,
                Feature.Status.TEMPORARILY_BLOCKED, Feature.Status.PERMISSION_REQUIRED}) {
            Feature.Status s = PermissionPolicy.evaluate(Feature.HVAC_FAN, Feature.Access.CONTROL, cap(limit));
            assertThat(s).isEqualTo(limit);
            assertThat(PermissionPolicy.usable(Feature.HVAC_FAN, s)).isFalse();
        }
    }

    @Test
    void unreportedCapabilityIsTreatedAsUnsupported() {
        assertThat(PermissionPolicy.evaluate(Feature.NAV_DESTINATION, Feature.Access.CONTROL, null))
                .isEqualTo(Feature.Status.NOT_SUPPORTED);
    }

    @Test
    void viewOnlyCannotControl() {
        Feature.Status s = PermissionPolicy.evaluate(Feature.HVAC_TEMPERATURE, Feature.Access.CONTROL,
                cap(Feature.Status.VIEW_ONLY));
        assertThat(s).isEqualTo(Feature.Status.VIEW_ONLY);
        assertThat(PermissionPolicy.usable(Feature.HVAC_TEMPERATURE, s)).isFalse();
    }

    @Test
    void mockOnlyIsUsableButStaysMock() {
        Feature.Status s = PermissionPolicy.evaluate(Feature.HVAC_TEMPERATURE, Feature.Access.CONTROL,
                cap(Feature.Status.MOCK_ONLY));
        assertThat(s).isEqualTo(Feature.Status.MOCK_ONLY);
        assertThat(PermissionPolicy.usable(Feature.HVAC_TEMPERATURE, s)).isTrue();
    }

    @Test
    void roleDefaultsGrantViewAndMapButNotHvacOrLightControl() {
        assertThat(Feature.NAV_DESTINATION.defaultAccess()).isEqualTo(Feature.Access.CONTROL);
        assertThat(Feature.HVAC_VIEW.defaultAccess()).isEqualTo(Feature.Access.VIEW);
        assertThat(Feature.HVAC_TEMPERATURE.defaultAccess()).isEqualTo(Feature.Access.NONE);
        assertThat(Feature.LIGHT_COLOR.defaultAccess()).isEqualTo(Feature.Access.NONE);
        assertThat(Feature.MUSIC_VIEW.defaultAccess()).isEqualTo(Feature.Access.VIEW);
        assertThat(Feature.MUSIC_CONTROL.defaultAccess()).isEqualTo(Feature.Access.NONE);
    }
}
