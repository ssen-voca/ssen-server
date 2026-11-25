package com.ssen.voca.auth;

import com.ssen.voca.auth.dto.AccessTokenResponse;
import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.RefreshRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;
	private final ClientIp clientIp;

	public AuthController(AuthService authService, ClientIp clientIp) {
		this.authService = authService;
		this.clientIp = clientIp;
	}

	@PostMapping("/signup")
	public ResponseEntity<TokenResponse> signup(
			@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
		return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request, clientIp.of(http)));
	}

	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		return authService.login(request, clientIp.of(http));
	}

	@PostMapping("/refresh")
	public AccessTokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return authService.refresh(request);
	}
}
