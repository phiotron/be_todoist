package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.push.PushTestResponse;
import com.taskly.dto.push.RegisterPushTokenRequest;
import com.taskly.dto.push.UnregisterPushTokenRequest;
import com.taskly.security.CurrentUser;
import com.taskly.service.PushTokenService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Device token FCM của app Android (M13c, F6). */
@RestController
@RequestMapping("/api/v1/me/push-tokens")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PushTokenController {

    private final PushTokenService pushTokens;

    public PushTokenController(PushTokenService pushTokens) {
        this.pushTokens = pushTokens;
    }

    /** 204; token đã thuộc tài khoản khác thì chuyển sang tài khoản này. */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@AuthenticationPrincipal Jwt jwt, @RequestBody RegisterPushTokenRequest request) {
        pushTokens.register(CurrentUser.id(jwt), request);
    }

    @PostMapping("/unregister")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@AuthenticationPrincipal Jwt jwt, @RequestBody UnregisterPushTokenRequest request) {
        pushTokens.unregister(CurrentUser.id(jwt), request);
    }

    /** Gửi thử một push tới mọi thiết bị đã đăng ký (202). */
    @PostMapping("/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PushTestResponse test(@AuthenticationPrincipal Jwt jwt) {
        return pushTokens.test(CurrentUser.id(jwt));
    }
}
