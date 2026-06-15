package com.globaltalenthub.controller;

import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.AuthService;
import com.globaltalenthub.service.AuthService.AuthContext;
import com.globaltalenthub.service.AuthService.AuthResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** App-owned auth: signup, login, and auth context. */
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** Atomic signup: create account + organization, return a bearer token. */
    @PostMapping("/api/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResult signup(@RequestBody Map<String, Object> body) {
        return authService.signup(body);
    }

    /** Email/password login; returns a bearer token + auth context. */
    @PostMapping("/api/auth/login")
    public AuthResult login(@RequestBody Map<String, Object> body) {
        return authService.login(body);
    }

    @GetMapping("/api/auth/me")
    public AuthContext me(@AuthenticationPrincipal AuthenticatedUser user) {
        return authService.me(user.userId(), user.email());
    }
}
