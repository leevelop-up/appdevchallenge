package com.codrive.server.domain;

import java.util.List;

public enum Feature {
    NAV_DESTINATION(Kind.CONTROL, Participant.Role.MAP, true),
    NAV_WAYPOINT(Kind.CONTROL, Participant.Role.MAP, true),
    HVAC_VIEW(Kind.VIEW, Participant.Role.HVAC, true),
    HVAC_TEMPERATURE(Kind.CONTROL, Participant.Role.HVAC, false),
    HVAC_FAN(Kind.CONTROL, Participant.Role.HVAC, false),
    LIGHT_VIEW(Kind.VIEW, Participant.Role.LIGHT, true),
    LIGHT_BRIGHTNESS(Kind.CONTROL, Participant.Role.LIGHT, false),
    LIGHT_COLOR(Kind.CONTROL, Participant.Role.LIGHT, false),
    MUSIC_VIEW(Kind.VIEW, Participant.Role.MUSIC, true),
    MUSIC_CONTROL(Kind.CONTROL, Participant.Role.MUSIC, false);

    public enum Kind { VIEW, CONTROL }

    public enum Access { NONE, VIEW, CONTROL }

    public enum Status {
        AVAILABLE, VIEW_ONLY, PERMISSION_REQUIRED, NOT_SUPPORTED, TEMPORARILY_BLOCKED, MOCK_ONLY;

        public boolean allowsControl() {
            return this == AVAILABLE || this == MOCK_ONLY;
        }
    }

    private final Kind kind;
    private final Participant.Role role;
    private final boolean grantedByDefault;

    Feature(Kind kind, Participant.Role role, boolean grantedByDefault) {
        this.kind = kind;
        this.role = role;
        this.grantedByDefault = grantedByDefault;
    }

    public Kind kind() {
        return kind;
    }

    public Participant.Role role() {
        return role;
    }

    public Access defaultAccess() {
        if (!grantedByDefault) {
            return Access.NONE;
        }
        return kind == Kind.VIEW ? Access.VIEW : Access.CONTROL;
    }

    public static List<Feature> forRole(Participant.Role role) {
        return java.util.Arrays.stream(values()).filter(f -> f.role == role).toList();
    }

    public static Feature parse(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
