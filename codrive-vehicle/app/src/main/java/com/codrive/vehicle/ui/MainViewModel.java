package com.codrive.vehicle.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.codrive.vehicle.App;
import com.codrive.vehicle.CoDriveController;

public class MainViewModel extends AndroidViewModel {
    private final CoDriveController controller;

    public MainViewModel(@NonNull Application app) {
        super(app);
        controller = ((App) app).controller();
    }

    public LiveData<CoDriveController.UiState> state() {
        return controller.state();
    }

    public CoDriveController controller() {
        return controller;
    }
}
