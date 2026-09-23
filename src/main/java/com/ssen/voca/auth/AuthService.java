package com.ssen.voca.auth;

import com.ssen.voca.auth.dto.AccessTokenResponse;
import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.RefreshRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	// ponytail: dummy BCrypt hash so a non-existent email still pays the encoder cost,
	// keeping login timing constant regardless of account existence.
	private static final String DUMMY_PASSWORD_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final AppUserRepository appUserRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;

	public AuthService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
		this.appUserRepository = appUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
	}

	@Transactional
	public TokenResponse signup(SignupRequest request) {
		if (appUserRepository.existsByEmail(request.email())) {
			throw new EmailAlreadyExistsException(request.email());
		}

		AppUser user = new AppUser(request.name(), request.email(), passwordEncoder.encode(request.password()));
		appUserRepository.save(user);

		return issueTokens(user);
	}

	public TokenResponse login(LoginRequest request) {
		AppUser user = appUserRepository.findByEmail(request.email()).orElse(null);
		String passwordHash = user != null ? user.getPasswordHash() : DUMMY_PASSWORD_HASH;

		boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);
		if (user == null || !passwordMatches) {
			throw new InvalidCredentialsException();
		}

		return issueTokens(user);
	}

	public AccessTokenResponse refresh(RefreshRequest request) {
		Long userId = Long.valueOf(jwtService.parseRefreshToken(request.refreshToken()).getSubject());
		AppUser user = appUserRepository.findById(userId)
				.orElseThrow(() -> new InvalidTokenException("존재하지 않는 사용자입니다."));

		return new AccessTokenResponse(jwtService.generateAccessToken(user.getId(), user.getEmail()));
	}

	private TokenResponse issueTokens(AppUser user) {
		return new TokenResponse(
				jwtService.generateAccessToken(user.getId(), user.getEmail()),
				jwtService.generateRefreshToken(user.getId()));
	}
}
