package com.ssen.voca.auth;

import com.ssen.voca.auth.dto.AccessTokenResponse;
import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.RefreshRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	// ponytail: dummy BCrypt hash so an unknown school / name still pays the encoder cost,
	// keeping login timing similar regardless of account existence.
	private static final String DUMMY_PIN_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
	// 학생 제한 키의 접두사. 교사 키(teacher-signup, teacher:<이메일>)와 겹치지 않게 한다.
	private static final String THROTTLE_PREFIX = "student:";

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
		String schoolKey = Schools.key(request.school());
		String nameKey = Names.key(request.name());
		String key = throttleKey(schoolKey, nameKey);
		// 가입 호출은 성공 여부와 무관하게 시도 1회로 센다 (PIN 탐색용으로 쓰일 수 있으므로).
		attemptLimiter.tryAcquire(key);
		// DB 유니크 제약은 해시된 PIN에 걸 수 없어서 같은 학교·같은 이름의 계정을 모두 BCrypt 비교한다.
		// ponytail: 동시에 같은 (학교, 이름, PIN)으로 가입하면 중복이 생길 수 있는 경합은 허용 — 필요하면 advisory lock.
		List<AppUser> sameName = appUserRepository.findAllBySchoolKeyAndNameKeyAndRole(
				schoolKey, nameKey, AppUser.STUDENT);
		if (findMatch(sameName, request.phoneLast4()) != null) {
			throw new StudentAlreadyExistsException();
		}
		AppUser user = new AppUser(
				Names.display(request.name()),
				nameKey,
				passwordEncoder.encode(request.phoneLast4()),
				Schools.display(request.school()),
				schoolKey);
		appUserRepository.save(user);
		return issueTokens(user);
	}

	public TokenResponse login(LoginRequest request) {
		String schoolKey = Schools.key(request.school());
		String nameKey = Names.key(request.name());
		String key = throttleKey(schoolKey, nameKey);
		// 선차감: 병렬 요청도 창당 한도를 넘지 못한다. 실패한 로그인은 차감을 그대로 두고, 성공하면 1회만 돌려준다.
		attemptLimiter.tryAcquire(key);
		List<AppUser> candidates = appUserRepository.findAllBySchoolKeyAndNameKeyAndRole(
				schoolKey, nameKey, AppUser.STUDENT);
		if (candidates.isEmpty()) {
			// 학교·이름이 있는지 드러내지 않도록 같은 401과 비슷한 처리 시간을 쓴다.
			passwordEncoder.matches(request.phoneLast4(), DUMMY_PIN_HASH);
		}
		AppUser user = findMatch(candidates, request.phoneLast4());
		if (user == null) {
			throw new InvalidCredentialsException();
		}
		attemptLimiter.release(key);
		return issueTokens(user);
	}

	public AccessTokenResponse refresh(RefreshRequest request) {
		Long userId = Long.valueOf(jwtService.parseRefreshToken(request.refreshToken()).getSubject());
		AppUser user = appUserRepository.findById(userId)
				.orElseThrow(() -> new InvalidTokenException("존재하지 않는 사용자입니다."));
		return new AccessTokenResponse(jwtService.generateAccessToken(user.getId(), user.getName(), user.getRole()));
	}

	private static String throttleKey(String schoolKey, String nameKey) {
		return THROTTLE_PREFIX + schoolKey + ":" + nameKey;
	}

	private AppUser findMatch(List<AppUser> candidates, String pin) {
		for (AppUser candidate : candidates) {
			if (passwordEncoder.matches(pin, candidate.getSecretHash())) {
				return candidate;
			}
		}
		return null;
	}

	private TokenResponse issueTokens(AppUser user) {
		return new TokenResponse(
				jwtService.generateAccessToken(user.getId(), user.getName(), user.getRole()),
				jwtService.generateRefreshToken(user.getId()));
	}
}
