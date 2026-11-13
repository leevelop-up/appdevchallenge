package com.codrive.vehicle.gateway;

import org.json.JSONObject;

import java.util.Map;

public interface VehicleGateway {
    String mode();

    void start();

    void release();

    void checkCapabilities(Callback<Map<String, Capability>> cb);

    void readState(String domain, Callback<Map<String, Object>> cb);

    void execute(String action, JSONObject params, Callback<Result> cb);

    void isDriving(Callback<Boolean> cb);

    interface Callback<T> {
        void onResult(T value);
    }

    final class Capability {
        public final String status;
        public final Double min;
        public final Double max;
        public final String reason;

        public Capability(String status, Double min, Double max, String reason) {
            this.status = status;
            this.min = min;
            this.max = max;
            this.reason = reason;
        }

        public static Capability of(String status) {
            return new Capability(status, null, null, null);
        }

        public static Capability unsupported(String reason) {
            return new Capability("NOT_SUPPORTED", null, null, reason);
        }

        public static final String NO_RESPONSE = "SDK 응답 없음";

        public static Capability noResponse() {
            return unsupported(NO_RESPONSE);
        }

        public boolean isNoResponse() {
            return NO_RESPONSE.equals(reason);
        }
    }

    final class Result {
        public final String status;
        public final String errorCode;
        public final boolean simulated;
        public final String message;

        public Result(String status, String errorCode, boolean simulated, String message) {
            this.status = status;
            this.errorCode = errorCode;
            this.simulated = simulated;
            this.message = message;
        }

        public static Result applied(boolean simulated) {
            return new Result("APPLIED", null, simulated, null);
        }

        public static Result failed(String errorCode, String message) {
            return new Result("FAILED", errorCode, false, message);
        }
    }
}
