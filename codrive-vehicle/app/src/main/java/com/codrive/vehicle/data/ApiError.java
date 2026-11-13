package com.codrive.vehicle.data;

public class ApiError extends Exception {
    public final int status;
    public final String code;

    public ApiError(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public boolean isUnauthorized() {
        return status == 401 || status == 403 && "UNAUTHORIZED".equals(code);
    }
}
