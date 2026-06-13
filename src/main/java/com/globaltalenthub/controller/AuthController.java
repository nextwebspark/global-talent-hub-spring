package com.globaltalenthub.controller;

import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.AuthService;
import com.globaltalenthub.service.AuthService.AuthContext;
import com.globaltalenthub.service.AuthService.SignupResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Org bootstrap + auth context. Port of auth.ts. */
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/api/auth/signup-org")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResult signupOrg(@RequestBody Map<String, Object> body,
                                  @AuthenticationPrincipal AuthenticatedUser user) {
        return authService.signupOrg(user.userId(), user.email(), body);
    }

    @GetMapping("/api/auth/me")
    public AuthContext me(@AuthenticationPrincipal AuthenticatedUser user) {
        return authService.me(user.userId(), user.email());
    }
}
