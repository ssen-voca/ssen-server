package com.ssen.voca.auth;

import com.ssen.voca.auth.dto.TeacherLoginRequest;
import com.ssen.voca.auth.dto.TeacherSignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teacher")
public class TeacherAuthController {

	private final TeacherAuthService teacherAuthService;

	public TeacherAuthController(TeacherAuthService teacherAuthService) {
		this.teacherAuthService = teacherAuthService;
	}

	@PostMapping("/signup")
	public ResponseEntity<TokenResponse> signup(@Valid @RequestBody TeacherSignupRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(teacherAuthService.signup(request));
	}

	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody TeacherLoginRequest request) {
		return teacherAuthService.login(request);
	}
}
