package com.codrive.vehicle.gateway;

import android.car.Car;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class UxRestrictionsSource {
    private static final String TAG = "UxRestrictions";

    private volatile Boolean restricted;
    private Car car;

    public void start(Context ctx) {
        try {
            car = Car.createCar(ctx, new Handler(Looper.getMainLooper()), 3000, (c, ready) -> {
                Log.i(TAG, "Car 서비스 ready=" + ready);
                if (!ready) {
                    restricted = null;
                    return;
                }
                try {
                    CarUxRestrictionsManager m = (CarUxRestrictionsManager) c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
                    restricted = m.getCurrentCarUxRestrictions().isRequiresDistractionOptimization();
                    Log.i(TAG, "현재 주행 규제(DO 필요)=" + restricted);
                    m.registerListener(r -> {
                        restricted = r.isRequiresDistractionOptimization();
                        Log.i(TAG, "주행 규제 변경(DO 필요)=" + restricted + " 활성 제한=0x"
                                + Integer.toHexString(r.getActiveRestrictions()));
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "CarUxRestrictionsManager 사용 불가", t);
                    restricted = null;
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "android.car 사용 불가", t);
            car = null;
            restricted = null;
        }
    }

    public Boolean restricted() {
        return restricted;
    }

    public void stop() {
        try {
            if (car != null) {
                car.disconnect();
            }
        } catch (Throwable ignored) {
        }
    }
}
