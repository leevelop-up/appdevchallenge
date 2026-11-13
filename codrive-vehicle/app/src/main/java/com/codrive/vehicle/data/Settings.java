package com.codrive.vehicle.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.codrive.vehicle.BuildConfig;

public class Settings {
    public static final String MODE_MOCK = "MOCK";
    public static final String MODE_PLEOS = "PLEOS";

    private final SharedPreferences p;

    public Settings(Context ctx) {
        p = ctx.getSharedPreferences("codrive_settings", Context.MODE_PRIVATE);
    }

    public String serverUrl() {
        if (!BuildConfig.DEBUG) {
            return BuildConfig.DEFAULT_SERVER_URL;
        }
        return p.getString("serverUrl", BuildConfig.DEFAULT_SERVER_URL);
    }

    public String mode() {
        if (!BuildConfig.DEBUG) {
            return MODE_PLEOS;
        }
        return p.getString("mode", MODE_MOCK);
    }

    public boolean confirmRouteChanges() {
        return p.getBoolean("confirmRouteChanges", true);
    }

    public void setConfirmRouteChanges(boolean on) {
        p.edit().putBoolean("confirmRouteChanges", on).apply();
    }

    public boolean mockDriving() {
        return BuildConfig.DEBUG && p.getBoolean("mockDriving", false);
    }

    public void save(String serverUrl, String mode, boolean mockDriving) {
        p.edit().putString("serverUrl", serverUrl.trim()).putString("mode", mode)
                .putBoolean("mockDriving", mockDriving).apply();
    }
}
