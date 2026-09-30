package com.ssen.voca.auth;

import com.ssen.voca.auth.dto.AccessTokenResponse;
import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.RefreshRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	// ponytail: dummy BCrypt hash so a name with no accounts still pays the encoder cost,
	// keeping login timing constant regardless of account existence.
	private static final String DUMMY_PIN_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final AppUserRepository appUserRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final LoginAttemptLimiter attemptLimiter;

	public AuthService(
			AppUserRepository appUserRepository,
			PasswordEncoder passwordEncoder,
			JwtService jwtService,
			LoginAttemptLimiter attemptLimiter) {
		this.appUserRepository = appUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.attemptLimiter = attemptLimiter;
	}

	@Transactional
	public TokenResponse signup(SignupRequest request) {
		String nameKey = nameKey(request.name());
		// 가입 호출은 성공 여부와 무관하게 시도 1회로 센다 (PIN 탐색용으로 쓰일 수 있으므로).
		attemptLimiter.checkAllowed(nameKey);
		attemptLimiter.recordAttempt(nameKey);

		// DB 유니크 제약은 해시된 PIN에 걸 수 없어서 같은 이름의 계정을 모두 BCrypt 비교한다.
		// ponytail: 동시에 같은 (이름, PIN)으로 가입하면 중복이 생길 수 있는 경합은 허용 — 필요하면 name_key advisory lock.
		if (findMatch(appUserRepository.findAllByNameKey(nameKey), request.phoneLast4()) != null) {
			throw new StudentAlreadyExistsException();
		}

		AppUser user = new AppUser(request.name().strip(), nameKey, passwordEncoder.encode(request.phoneLast4()));
		appUserRepository.save(user);

		return issueTokens(user);
	}

	public TokenResponse login(LoginRequest request) {
		String nameKey = nameKey(request.name());
		attemptLimiter.checkAllowed(nameKey);

		List<AppUser> candidates = appUserRepository.findAllByNameKey(nameKey);
		if (candidates.isEmpty()) {
			passwordEncoder.matches(request.phoneLast4(), DUMMY_PIN_HASH);
		}

		AppUser user = findMatch(candidates, request.phoneLast4());
		if (user == null) {
			attemptLimiter.recordAttempt(nameKey);
			throw new InvalidCredentialsException();
		}

		attemptLimiter.clear(nameKey);
		return issueTokens(user);
	}

	public AccessTokenResponse refresh(RefreshRequest request) {
		Long userId = Long.valueOf(jwtService.parseRefreshToken(request.refreshToken()).getSubject());
		AppUser user = appUserRepository.findById(userId)
				.orElseThrow(() -> new InvalidTokenException("존재하지 않는 사용자입니다."));

		return new AccessTokenResponse(jwtService.generateAccessToken(user.getId(), user.getName()));
	}

	private AppUser findMatch(List<AppUser> candidates, String pin) {
		for (AppUser candidate : candidates) {
			if (passwordEncoder.matches(pin, candidate.getPinHash())) {
				return candidate;
			}
		}
		return null;
	}

	private static String nameKey(String name) {
		return name.strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
	}

	private TokenResponse issueTokens(AppUser user) {
		return new TokenResponse(
				jwtService.generateAccessToken(user.getId(), user.getName()),
				jwtService.generateRefreshToken(user.getId()));
	}
}
