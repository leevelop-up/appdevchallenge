package com.codrive.vehicle;

import android.app.Application;

public class App extends Application {
    private CoDriveController controller;

    @Override
    public void onCreate() {
        super.onCreate();
        controller = new CoDriveController(this);

        controller.start();
    }

    public CoDriveController controller() {
        return controller;
    }
}
