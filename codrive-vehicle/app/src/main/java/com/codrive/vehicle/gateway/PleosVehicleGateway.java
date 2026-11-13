package com.codrive.vehicle.gateway;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.constants.RouteOption;
import ai.pleos.playground.navi.constants.WaypointIndex;
import ai.pleos.playground.navi.data.RequestWaypointInfo;
import ai.pleos.playground.navi.data.RouteInfo;
import ai.pleos.playground.navi.data.RouteStartInfo;
import ai.pleos.playground.navi.data.WaypointChangedInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.HVAC;
import ai.pleos.playground.vehicle.api.Light;
import ai.pleos.playground.vehicle.api.model.area.SeatArea;
import ai.pleos.playground.vehicle.api.model.capability.AccessType;
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds;
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig;
import ai.pleos.playground.vehicle.constant.area.SeatValue;
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit;
import kotlin.Unit;

public class PleosVehicleGateway implements VehicleGateway {
    private static final String TAG = "PleosGateway";

    private static final SeatArea AREA = new SeatArea(SeatValue.ROW_1_LEFT);
    private volatile SeatArea tempArea = AREA;
    private volatile SeatArea fanArea = AREA;
    private volatile SeatArea brightArea = AREA;
    private static final long NAV_TIMEOUT_MS = 25000;

    private final Context context;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private Vehicle vehicle;
    private NaviHelper navi;
    private volatile boolean naviReady;
    private volatile String naviFailure = "NaviHelper 초기화 전";

    private volatile Callback<Result> pendingNav;
    private final UxRestrictionsSource ux = new UxRestrictionsSource();

    private final InAppMusicPlayer music;

    public PleosVehicleGateway(Context context) {
        this.context = context.getApplicationContext();
        this.music = InAppMusicPlayer.get(this.context);
        this.music.startMediaService();
    }

    @Override
    public String mode() {
        return "PLEOS";
    }

    @Override
    public void start() {
        ux.start(context);
        try {
            vehicle = new Vehicle(context);
            vehicle.initialize();
        } catch (Throwable t) {
            Log.w(TAG, "Vehicle SDK 초기화 실패", t);
            vehicle = null;
        }
        try {
            navi = new NaviHelper(context);
            navi.addListener(naviListener);
            navi.initialize(() -> {
                naviReady = true;
                return Unit.INSTANCE;
            }, t -> {
                naviReady = false;
                naviFailure = String.valueOf(t);
                return Unit.INSTANCE;
            });
        } catch (Throwable t) {
            Log.w(TAG, "NaviHelper 초기화 실패", t);
            naviFailure = String.valueOf(t);
            navi = null;
        }
    }

    @Override
    public void release() {
        ux.stop();
        music.release();
        try {
            if (navi != null) {
                navi.removeListener(naviListener);
                navi.release();
            }
            if (vehicle != null) {
                vehicle.release();
            }
        } catch (Throwable t) {
            Log.w(TAG, "release 실패", t);
        }
        timer.shutdownNow();
    }

    @Override
    public void checkCapabilities(Callback<Map<String, Capability>> cb) {
        Map<String, Capability> out = new ConcurrentHashMap<>();
        String[] keys = {"HVAC_VIEW", "HVAC_TEMPERATURE", "HVAC_FAN", "LIGHT_VIEW", "LIGHT_BRIGHTNESS"};
        AtomicInteger remaining = new AtomicInteger(3);
        AtomicBoolean done = new AtomicBoolean(false);
        Runnable finish = () -> {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            for (String k : keys) {
                out.putIfAbsent(k, Capability.noResponse());
            }
            Map<String, Capability> ordered = new LinkedHashMap<>();

            Capability nav = naviReady ? Capability.of("AVAILABLE") : Capability.unsupported(naviFailure);
            ordered.put("NAV_DESTINATION", nav);
            ordered.put("NAV_WAYPOINT", nav);
            for (String k : keys) {
                ordered.put(k, out.get(k));
            }

            ordered.put("MUSIC_VIEW", Capability.of("AVAILABLE"));
            ordered.put("MUSIC_CONTROL", Capability.of("AVAILABLE"));
            ordered.put("LIGHT_COLOR", Capability.unsupported(
                    "setAmbientLightBrightnessColor 의 값 의미(색상 인덱스/범위)가 공식 문서에 없어 미지원으로 보고"));
            cb.onResult(ordered);
        };
        Runnable one = () -> {
            if (remaining.decrementAndGet() == 0) {
                finish.run();
            }
        };
        timer.schedule(finish, 5, TimeUnit.SECONDS);

        if (vehicle == null) {
            for (String k : keys) {
                out.put(k, Capability.unsupported("Vehicle SDK 초기화 실패"));
            }
            finish.run();
            return;
        }
        HVAC hvac = vehicle.getHVAC();
        Light light = vehicle.getLight();
        try {
            hvac.checkHvacTemperatureCapabilities((config, map) -> {
                tempArea = pickArea(map);
                AreaBounds<Float> b = map.get(tempArea);
                Capability c = fromAccess(config, b == null ? null : (double) b.getMinValue(),
                        b == null ? null : (double) b.getMaxValue());
                out.put("HVAC_TEMPERATURE", c);
                out.put("HVAC_VIEW", viewOf(config));
                one.run();
                return Unit.INSTANCE;
            }, e -> {
                Capability c = fromError(e);
                out.put("HVAC_TEMPERATURE", c);
                out.put("HVAC_VIEW", c);
                one.run();
                return Unit.INSTANCE;
            });
            hvac.checkHvacFanSpeedCapabilities((config, map) -> {
                fanArea = pickArea(map);
                AreaBounds<Integer> b = map.get(fanArea);
                out.put("HVAC_FAN", fromAccess(config, b == null ? null : (double) b.getMinValue(),
                        b == null ? null : (double) b.getMaxValue()));
                one.run();
                return Unit.INSTANCE;
            }, e -> {
                out.put("HVAC_FAN", fromError(e));
                one.run();
                return Unit.INSTANCE;
            });
            light.checkAmbientLightBrightnessCapabilities((config, map) -> {
                brightArea = pickArea(map);
                AreaBounds<Integer> b = map.get(brightArea);
                out.put("LIGHT_BRIGHTNESS", fromAccess(config, b == null ? null : (double) b.getMinValue(),
                        b == null ? null : (double) b.getMaxValue()));
                out.put("LIGHT_VIEW", viewOf(config));
                one.run();
                return Unit.INSTANCE;
            }, e -> {
                Capability c = fromError(e);
                out.put("LIGHT_BRIGHTNESS", c);
                out.put("LIGHT_VIEW", c);
                one.run();
                return Unit.INSTANCE;
            });
        } catch (Throwable t) {
            Log.w(TAG, "capability 조회 실패", t);
            for (String k : keys) {
                out.putIfAbsent(k, fromError(t));
            }
            finish.run();
        }
    }

    private static <T> SeatArea pickArea(Map<SeatArea, T> map) {
        if (map == null || map.isEmpty() || map.containsKey(AREA)) {
            return AREA;
        }
        return map.keySet().iterator().next();
    }

    private static Capability fromAccess(CapabilityConfig config, Double min, Double max) {
        AccessType t = config.getAccessType();
        if (t == AccessType.READ_WRITE || t == AccessType.WRITE) {
            return new Capability("AVAILABLE", min, max, null);
        }
        if (t == AccessType.READ) {
            return new Capability("VIEW_ONLY", min, max, "차량이 읽기만 허용");
        }
        return Capability.unsupported("accessType=NONE");
    }

    private static Capability viewOf(CapabilityConfig config) {
        AccessType t = config.getAccessType();
        return (t == AccessType.READ || t == AccessType.READ_WRITE)
                ? Capability.of("AVAILABLE") : Capability.unsupported("읽기 불가(accessType=" + t + ")");
    }

    private static Capability fromError(Throwable e) {
        String s = (e.getClass().getSimpleName() + " " + e.getMessage()).toUpperCase();
        if (s.contains("PERMISSION") || s.contains("SECURITY")) {
            return new Capability("PERMISSION_REQUIRED", null, null, String.valueOf(e));
        }
        return Capability.unsupported(String.valueOf(e));
    }

    @Override
    public void readState(String domain, Callback<Map<String, Object>> cb) {
        Map<String, Object> v = new ConcurrentHashMap<>();
        if ("MUSIC".equals(domain)) {
            cb.onResult(music.state());
            return;
        }
        if (vehicle == null) {
            cb.onResult(v);
            return;
        }
        AtomicInteger remaining = new AtomicInteger(2);
        Runnable one = () -> {
            if (remaining.decrementAndGet() == 0) {
                cb.onResult(new LinkedHashMap<>(v));
            }
        };
        try {
            if ("HVAC".equals(domain)) {
                vehicle.getHVAC().getHvacTemperature(tempArea, TemperatureUnit.CELSIUS, t -> {
                    v.put("temperature", t);
                    one.run();
                    return Unit.INSTANCE;
                }, e -> {
                    Log.w(TAG, "온도 조회 실패: " + e);
                    one.run();
                    return Unit.INSTANCE;
                });
                vehicle.getHVAC().getHvacFanSpeed(fanArea, f -> {
                    v.put("fanSpeed", f);
                    one.run();
                    return Unit.INSTANCE;
                }, e -> {
                    one.run();
                    return Unit.INSTANCE;
                });
            } else if ("LIGHT".equals(domain)) {
                remaining.set(1);
                vehicle.getLight().getAmbientLightBrightness(brightArea, b -> {
                    v.put("brightness", b);
                    one.run();
                    return Unit.INSTANCE;
                }, e -> {
                    one.run();
                    return Unit.INSTANCE;
                });
            } else {
                cb.onResult(v);
            }
        } catch (Throwable t) {
            Log.w(TAG, "상태 조회 실패", t);
            cb.onResult(new LinkedHashMap<>(v));
        }
    }

    @Override
    public void execute(String action, JSONObject p, Callback<Result> cb) {
        try {
            switch (action) {
                case "HVAC_TEMPERATURE" -> setTemperature((float) p.getDouble("celsius"), cb);
                case "HVAC_FAN" -> setFan(p.getInt("level"), cb);
                case "LIGHT_BRIGHTNESS" -> setBrightness(p.getInt("level"), cb);
                case "NAV_DESTINATION" -> requestRoute(p, cb);
                case "NAV_WAYPOINT" -> addWaypoint(p, cb);
                case "MUSIC_CONTROL" -> music.execute(p.getString("command"), cb);
                default -> cb.onResult(new Result("NOT_SUPPORTED", "UNSUPPORTED_ACTION", false,
                        "실제 차량 SDK 에서 지원하지 않는 기능: " + action));
            }
        } catch (Throwable t) {
            Log.w(TAG, "제어 실패", t);
            cb.onResult(failure(t));
        }
    }

    private static final int VERIFY_ATTEMPTS = 8;
    private static final long VERIFY_INTERVAL_MS = 500;

    private interface Reader {
        void read(Callback<Double> onValue, Callback<Throwable> onError);
    }

    private void verify(Reader reader, double want, double tolerance, String label, Callback<Result> cb, int attempt) {
        reader.read(now -> {
            if (Math.abs(now - want) <= tolerance) {
                cb.onResult(Result.applied(false));
            } else if (attempt < VERIFY_ATTEMPTS) {
                timer.schedule(() -> verify(reader, want, tolerance, label, cb, attempt + 1),
                        VERIFY_INTERVAL_MS, TimeUnit.MILLISECONDS);
            } else {
                cb.onResult(Result.failed("VERIFY_MISMATCH", label + " 요청 " + want + " / "
                        + (VERIFY_ATTEMPTS * VERIFY_INTERVAL_MS / 1000) + "초 뒤에도 읽은 값 " + now));
            }
        }, err -> cb.onResult(failure(err)));
    }

    private void setTemperature(float celsius, Callback<Result> cb) {
        HVAC hvac = requireVehicle().getHVAC();
        SeatArea area = tempArea;
        hvac.setHvacTemperature(area, celsius, TemperatureUnit.CELSIUS, (a, applied) -> {
            verify((ok, err) -> hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, t -> {
                ok.onResult((double) t);
                return Unit.INSTANCE;
            }, e -> {
                err.onResult(e);
                return Unit.INSTANCE;
            }), celsius, 0.6, "온도", cb, 0);
            return Unit.INSTANCE;
        }, e -> {
            cb.onResult(failure(e));
            return Unit.INSTANCE;
        });
    }

    private void setFan(int level, Callback<Result> cb) {
        HVAC hvac = requireVehicle().getHVAC();
        SeatArea area = fanArea;
        hvac.setHvacFanSpeed(area, level, (a, applied) -> {
            verify((ok, err) -> hvac.getHvacFanSpeed(area, f -> {
                ok.onResult((double) f);
                return Unit.INSTANCE;
            }, e -> {
                err.onResult(e);
                return Unit.INSTANCE;
            }), level, 0.0, "풍량", cb, 0);
            return Unit.INSTANCE;
        }, e -> {
            cb.onResult(failure(e));
            return Unit.INSTANCE;
        });
    }

    private void setBrightness(int level, Callback<Result> cb) {
        Light light = requireVehicle().getLight();
        SeatArea area = brightArea;
        light.setAmbientLightBrightness(area, level, (a, applied) -> {
            verify((ok, err) -> light.getAmbientLightBrightness(area, b -> {
                ok.onResult((double) b);
                return Unit.INSTANCE;
            }, e -> {
                err.onResult(e);
                return Unit.INSTANCE;
            }), level, 0.0, "밝기", cb, 0);
            return Unit.INSTANCE;
        }, e -> {
            cb.onResult(failure(e));
            return Unit.INSTANCE;
        });
    }

    private void requestRoute(JSONObject p, Callback<Result> cb) throws Exception {
        NaviHelper n = requireNavi();
        double lat = p.getDouble("lat"), lng = p.getDouble("lng");
        String name = p.getString("name");
        armNav(cb);

        n.requestRoute(new RouteInfo(lat, lng, lat, lng, name, "", "", "", RouteOption.RECOMMENDED, "", false));
    }

    private void addWaypoint(JSONObject p, Callback<Result> cb) throws Exception {
        NaviHelper n = requireNavi();
        armNav(cb);
        n.addWaypoint(new RequestWaypointInfo(p.getDouble("lat"), p.getDouble("lng"), p.getString("name"),
                WaypointIndex.FIRST, "", "", "", ""));
    }

    private void armNav(Callback<Result> cb) {
        pendingNav = cb;
        timer.schedule(() -> {
            Callback<Result> c = pendingNav;
            if (c == cb) {
                pendingNav = null;
                c.onResult(Result.failed("NAVI_NO_RESPONSE", "내비게이션 앱에서 응답이 없습니다."));
            }
        }, NAV_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void finishNav(Result r) {
        Callback<Result> c = pendingNav;
        if (c != null) {
            pendingNav = null;
            c.onResult(r);
        }
    }

    private final NaviHelperEventListener naviListener = new NaviHelperEventListener() {
        @Override
        public void onRouteStarted(RouteStartInfo info) {
            finishNav(Result.applied(false));
        }

        @Override
        public void onWaypointChanged(WaypointChangedInfo info) {
            finishNav(Result.applied(false));
        }

        @Override
        public void onError(NaviErrorCode code) {
            finishNav(Result.failed("NAVI_" + code, "내비게이션 오류: " + code));
        }
    };

    private Vehicle requireVehicle() {
        if (vehicle == null) {
            throw new IllegalStateException("Vehicle SDK 가 초기화되지 않았습니다.");
        }
        return vehicle;
    }

    private NaviHelper requireNavi() {
        if (navi == null || !naviReady) {
            throw new IllegalStateException("NaviHelper 사용 불가: " + naviFailure);
        }
        return navi;
    }

    private static Result failure(Throwable e) {
        Capability c = fromError(e);
        if ("PERMISSION_REQUIRED".equals(c.status)) {
            return new Result("PERMISSION_DENIED", "SDK_PERMISSION", false, String.valueOf(e));
        }
        return Result.failed("SDK_ERROR", String.valueOf(e));
    }

    @Override
    public void isDriving(Callback<Boolean> cb) {
        Boolean r = ux.restricted();
        cb.onResult(r == null ? Boolean.TRUE : r);
    }
}
