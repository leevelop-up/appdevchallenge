package com.codrive.vehicle.gateway;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public class MockVehicleGateway implements VehicleGateway {
    private double temperature = 22.0;
    private int fan = 3;
    private int brightness = 50;
    private String color = "#FFFFFF";
    private String destination = null;
    private boolean playing = false;
    private int track = 0;
    private static final String[] TRACKS = {"Mock Track 1", "Mock Track 2", "Mock Track 3"};
    private volatile boolean driving = false;

    private volatile boolean failNext = false;

    public void setDriving(boolean driving) {
        this.driving = driving;
    }

    public void failNext() {
        this.failNext = true;
    }

    @Override
    public String mode() {
        return "MOCK";
    }

    @Override
    public void start() {
    }

    @Override
    public void release() {
    }

    @Override
    public void checkCapabilities(Callback<Map<String, Capability>> cb) {
        Map<String, Capability> m = new LinkedHashMap<>();
        m.put("NAV_DESTINATION", Capability.of("MOCK_ONLY"));
        m.put("NAV_WAYPOINT", Capability.of("MOCK_ONLY"));
        m.put("HVAC_VIEW", Capability.of("MOCK_ONLY"));
        m.put("HVAC_TEMPERATURE", new Capability("MOCK_ONLY", 18.0, 28.0, null));
        m.put("HVAC_FAN", new Capability("MOCK_ONLY", 0.0, 8.0, null));
        m.put("LIGHT_VIEW", Capability.of("MOCK_ONLY"));
        m.put("LIGHT_BRIGHTNESS", new Capability("MOCK_ONLY", 0.0, 100.0, null));
        m.put("LIGHT_COLOR", Capability.of("MOCK_ONLY"));
        m.put("MUSIC_VIEW", Capability.of("MOCK_ONLY"));
        m.put("MUSIC_CONTROL", Capability.of("MOCK_ONLY"));
        cb.onResult(m);
    }

    @Override
    public synchronized void readState(String domain, Callback<Map<String, Object>> cb) {
        Map<String, Object> v = new LinkedHashMap<>();
        if ("HVAC".equals(domain)) {
            v.put("temperature", temperature);
            v.put("fanSpeed", fan);
        } else if ("MUSIC".equals(domain)) {
            v.put("playing", playing);
            v.put("title", TRACKS[track]);
        } else if ("LIGHT".equals(domain)) {
            v.put("brightness", brightness);
            v.put("color", color);
        }
        cb.onResult(v);
    }

    @Override
    public synchronized void execute(String action, JSONObject p, Callback<Result> cb) {
        if (failNext) {
            failNext = false;
            cb.onResult(new Result("FAILED", "MOCK_INJECTED_FAILURE", true, "시뮬레이션 실패"));
            return;
        }
        switch (action) {
            case "HVAC_TEMPERATURE" -> temperature = p.optDouble("celsius", temperature);
            case "HVAC_FAN" -> fan = p.optInt("level", fan);
            case "LIGHT_BRIGHTNESS" -> brightness = p.optInt("level", brightness);
            case "LIGHT_COLOR" -> color = p.optString("hex", color);
            case "NAV_DESTINATION" -> destination = p.optString("name");
            case "MUSIC_CONTROL" -> {
                switch (p.optString("command")) {
                    case "PLAY" -> playing = true;
                    case "PAUSE" -> playing = false;
                    case "NEXT" -> track = (track + 1) % TRACKS.length;
                    case "PREVIOUS" -> track = (track + TRACKS.length - 1) % TRACKS.length;
                    default -> {
                        cb.onResult(new Result("NOT_SUPPORTED", "UNKNOWN_COMMAND", true, null));
                        return;
                    }
                }
            }
            case "NAV_WAYPOINT" -> {
            }
            default -> {
                cb.onResult(new Result("NOT_SUPPORTED", "UNKNOWN_ACTION", true, null));
                return;
            }
        }
        cb.onResult(Result.applied(true));
    }

    public synchronized String destination() {
        return destination;
    }

    @Override
    public void isDriving(Callback<Boolean> cb) {
        cb.onResult(driving);
    }
}
