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
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	// ponytail: dummy BCrypt hash so a name with no accounts still pays the encoder cost,
	// keeping login timing constant regardless of account existence.
	private static final String DUMMY_PIN_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	// 폭 없는 문자는 눈에 보이지 않으므로 이름 정규화에서 제거한다.
	private static final Pattern INVISIBLE = Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]");
	private static final int MAX_NAME_LENGTH = 50;

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
		attemptLimiter.tryAcquire(nameKey);

		// DB 유니크 제약은 해시된 PIN에 걸 수 없어서 같은 이름의 계정을 모두 BCrypt 비교한다.
		// ponytail: 동시에 같은 (이름, PIN)으로 가입하면 중복이 생길 수 있는 경합은 허용 — 필요하면 name_key advisory lock.
		if (findMatch(appUserRepository.findAllByNameKeyAndRole(nameKey, AppUser.STUDENT), request.phoneLast4()) != null) {
			throw new StudentAlreadyExistsException();
		}

		AppUser user = new AppUser(displayName(request.name()), nameKey, passwordEncoder.encode(request.phoneLast4()));
		appUserRepository.save(user);

		return issueTokens(user);
	}

	public TokenResponse login(LoginRequest request) {
		String nameKey = nameKey(request.name());
		// 선차감: 병렬 요청도 창당 한도를 넘지 못한다. 실패한 로그인은 차감을 그대로 두고, 성공하면 1회만 돌려준다.
		attemptLimiter.tryAcquire(nameKey);

		List<AppUser> candidates = appUserRepository.findAllByNameKeyAndRole(nameKey, AppUser.STUDENT);
		if (candidates.isEmpty()) {
			passwordEncoder.matches(request.phoneLast4(), DUMMY_PIN_HASH);
		}

		AppUser user = findMatch(candidates, request.phoneLast4());
		if (user == null) {
			throw new InvalidCredentialsException();
		}

		attemptLimiter.release(nameKey);
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
			if (passwordEncoder.matches(pin, candidate.getSecretHash())) {
				return candidate;
			}
		}
		return null;
	}

	private static String displayName(String name) {
		return INVISIBLE.matcher(name).replaceAll("").strip();
	}

	/** 정규화한 이름 키. 비었거나 50자를 넘으면(소문자화로 길어질 수 있다) 제한 카운터·DB에 닿기 전에 거절한다. */
	private static String nameKey(String name) {
		String key = displayName(name).replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
		if (key.isBlank()) {
			throw new InvalidNameException(InvalidNameException.BLANK);
		}
		if (key.length() > MAX_NAME_LENGTH) {
			throw new InvalidNameException(InvalidNameException.TOO_LONG);
		}
		return key;
	}

	private TokenResponse issueTokens(AppUser user) {
		return new TokenResponse(
				jwtService.generateAccessToken(user.getId(), user.getName()),
				jwtService.generateRefreshToken(user.getId()));
	}
}
