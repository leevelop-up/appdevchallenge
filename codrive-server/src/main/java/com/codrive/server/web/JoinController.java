package com.codrive.server.web;

import com.codrive.server.service.ApiException;
import com.codrive.server.service.RateLimiter;
import com.codrive.server.service.SessionService;
import com.codrive.server.service.Views;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class JoinController {
    record JoinReq(String token, String code, @NotBlank String nickname) {
    }

    record JoinRes(UUID requestId, String participantToken, String sessionAlias) {
    }

    private final SessionService sessions;
    private final RateLimiter limiter;

    @GetMapping("/")
    String index() {
        return "redirect:/join";
    }

    @GetMapping("/join")
    String page(@RequestParam(required = false) String token, Model model, HttpServletRequest req) {
        Views.JoinPreview preview = null;
        if (token != null && !token.isBlank() && limiter.tryAcquire("preview:" + req.getRemoteAddr(), 30, 60_000)) {
            preview = sessions.preview(token, null);
        }
        model.addAttribute("token", token == null ? "" : token);
        model.addAttribute("preview", preview);
        return "join";
    }

    @GetMapping("/api/join/preview")
    @ResponseBody
    Views.JoinPreview preview(@RequestParam(required = false) String token,
                              @RequestParam(required = false) String code, HttpServletRequest req) {
        if (!limiter.tryAcquire("preview:" + req.getRemoteAddr(), 30, 60_000)) {
            throw ApiException.tooMany("잠시 후 다시 시도해주세요.");
        }
        return sessions.preview(token, code);
    }

    @PostMapping("/api/join/requests")
    @ResponseBody
    @ResponseStatus(HttpStatus.CREATED)
    JoinRes join(@Valid @RequestBody JoinReq body, HttpServletRequest req) {
        if (!limiter.tryAcquire("join:" + req.getRemoteAddr(), 10, 60_000)) {
            throw ApiException.tooMany("요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");
        }
        SessionService.JoinResult r = sessions.createJoinRequest(body.token(), body.code(), body.nickname());
        return new JoinRes(r.requestId(), r.participantToken(), r.sessionAlias());
    }
}
