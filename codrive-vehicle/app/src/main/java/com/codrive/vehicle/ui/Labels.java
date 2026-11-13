package com.codrive.vehicle.ui;

import com.codrive.vehicle.model.Models;

import java.util.ArrayList;
import java.util.List;

public final class Labels {
    private Labels() {
    }

    public static String role(String role) {
        if (role == null) {
            return "역할 미배정";
        }
        return switch (role) {
            case "MAP" -> "지도 담당";
            case "HVAC" -> "공조 담당";
            case "LIGHT" -> "조명 담당";
            case "MUSIC" -> "음악 담당";
            default -> "역할 미배정";
        };
    }

    public static String feature(String code) {
        return switch (code) {
            case "NAV_DESTINATION" -> "목적지 요청";
            case "NAV_WAYPOINT" -> "경유지 제안";
            case "HVAC_VIEW" -> "공조 상태 조회";
            case "HVAC_TEMPERATURE" -> "온도 변경";
            case "HVAC_FAN" -> "풍량 변경";
            case "LIGHT_VIEW" -> "조명 상태 조회";
            case "LIGHT_BRIGHTNESS" -> "밝기 변경";
            case "LIGHT_COLOR" -> "색상 변경";
            case "MUSIC_VIEW" -> "재생 상태 조회";
            case "MUSIC_CONTROL" -> "음악 제어";
            default -> code;
        };
    }

    public static String status(String status) {
        return switch (status) {
            case "AVAILABLE" -> "사용 가능";
            case "VIEW_ONLY" -> "조회만 가능";
            case "PERMISSION_REQUIRED" -> "권한 확인 필요";
            case "NOT_SUPPORTED" -> "미지원";
            case "TEMPORARILY_BLOCKED" -> "일시 제한";
            case "MOCK_ONLY" -> "시뮬레이션(MOCK)";
            default -> status;
        };
    }

    public static String connection(String c) {
        return "ONLINE".equals(c) ? "연결됨" : "연결 끊김(재접속 대기)";
    }

    public static String[] summarize(Models.ParticipantInfo p) {
        List<String> ok = new ArrayList<>(), need = new ArrayList<>(), no = new ArrayList<>();
        for (Models.FeatureInfo f : p.features) {
            String name = feature(f.code);
            if (f.isUsable()) {
                ok.add("MOCK_ONLY".equals(f.status) ? name + "(MOCK)" : name);
            } else if ("PERMISSION_REQUIRED".equals(f.status)) {
                need.add(name);
            } else {
                no.add(name + "(" + status(f.status) + ")");
            }
        }
        return new String[]{
                ok.isEmpty() ? "허용 기능: 없음" : "허용 기능: " + String.join(", ", ok),
                need.isEmpty() ? "" : "권한 확인 필요: " + String.join(", ", need),
                no.isEmpty() ? "" : "사용 불가: " + String.join(", ", no)
        };
    }
}
