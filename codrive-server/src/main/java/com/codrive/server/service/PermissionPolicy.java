package com.codrive.server.service;

import com.codrive.server.domain.Feature;

public final class PermissionPolicy {
    private PermissionPolicy() {
    }

    public static Feature.Status evaluate(Feature f, Feature.Access granted, CapabilityStore.Entry cap) {
        if (f.kind() == Feature.Kind.VIEW && granted == Feature.Access.NONE) {
            return null;
        }
        if (cap == null) {
            return Feature.Status.NOT_SUPPORTED;
        }
        Feature.Status s = cap.status();
        if (s == Feature.Status.NOT_SUPPORTED || s == Feature.Status.TEMPORARILY_BLOCKED
                || s == Feature.Status.PERMISSION_REQUIRED) {
            return s;
        }
        if (f.kind() == Feature.Kind.CONTROL && granted != Feature.Access.CONTROL) {
            return Feature.Status.PERMISSION_REQUIRED;
        }
        return s;
    }

    public static boolean usable(Feature f, Feature.Status status) {
        if (status == null) {
            return false;
        }
        if (f.kind() == Feature.Kind.VIEW) {
            return status == Feature.Status.AVAILABLE || status == Feature.Status.VIEW_ONLY
                    || status == Feature.Status.MOCK_ONLY;
        }
        return status.allowsControl();
    }
}
