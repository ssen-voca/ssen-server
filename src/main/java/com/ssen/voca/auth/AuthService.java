package com.ssen.voca.auth;

import com.ssen.voca.auth.dto.AccessTokenResponse;
import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.RefreshRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	// ponytail: dummy BCrypt hash so an unknown school / name still pays the encoder cost,
	// keeping login timing similar regardless of account existence.
	private static final String DUMMY_PIN_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
	// 학생·교사가 같은 로그인을 쓰므로 제한 키도 같다.
	// 제한은 두 겹이다: (학교, 이름, 접속 IP)당 max-attempts, 그리고 (학교, 이름)당 더 느슨한 account-max-attempts.
	// 학생이 교사 이름으로 틀려도 다른 IP의 교사는 잠기지 않고, 여러 IP로 번호를 대입하는 것도 계정 상한이 막는다.
	private static final String THROTTLE_PREFIX = "student:";
	private static final String IP_THROTTLE_PREFIX = "ip:";

	private final AppUserRepository appUserRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final LoginAttemptLimiter attemptLimiter;
	private final int accountMaxAttempts;

	public AuthService(
			AppUserRepository appUserRepository,
			PasswordEncoder passwordEncoder,
			JwtService jwtService,
			LoginAttemptLimiter attemptLimiter,
			@Value("${app.auth.account-max-attempts:100}") int accountMaxAttempts) {
		this.appUserRepository = appUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.attemptLimiter = attemptLimiter;
		this.accountMaxAttempts = accountMaxAttempts;
	}

	@Transactional
	public TokenResponse signup(SignupRequest request, String clientIp) {
		String schoolKey = Schools.key(request.school());
		String nameKey = Names.key(request.name());
		// 가입 호출은 성공 여부와 무관하게 시도 1회로 센다 (PIN 탐색용으로 쓰일 수 있으므로).
		acquireAttempt(schoolKey, nameKey, clientIp);
		// DB 유니크 제약은 해시된 PIN에 걸 수 없어서 같은 학교·같은 이름의 계정(교사 포함)을 모두 BCrypt 비교한다.
		// 교사 번호와 같으면 409이고, 번호가 다르면 별개의 학생 계정이라 교사 권한은 얻지 못한다.
		// ponytail: 동시에 같은 (학교, 이름, PIN)으로 가입하면 중복이 생길 수 있는 경합은 허용 — 필요하면 advisory lock.
		List<AppUser> sameName = findCandidates(schoolKey, nameKey);
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

	public TokenResponse login(LoginRequest request, String clientIp) {
		String schoolKey = Schools.key(request.school());
		String nameKey = Names.key(request.name());
		// 선차감: 병렬 요청도 창당 한도를 넘지 못한다. 실패한 로그인은 차감을 그대로 두고, 성공하면 1회만 돌려준다.
		acquireAttempt(schoolKey, nameKey, clientIp);
		// 비활성 교사는 없는 계정과 똑같이 다룬다 (같은 401, 같은 BCrypt 비용).
		List<AppUser> candidates = findCandidates(schoolKey, nameKey).stream()
				.filter(candidate -> !candidate.isDisabled())
				.toList();
		if (candidates.isEmpty()) {
			// 학교·이름이 있는지 드러내지 않도록 같은 401과 비슷한 처리 시간을 쓴다.
			passwordEncoder.matches(request.phoneLast4(), DUMMY_PIN_HASH);
		}
		AppUser user = findMatch(candidates, request.phoneLast4());
		if (user == null) {
			throw new InvalidCredentialsException();
		}
		releaseAttempt(schoolKey, nameKey, clientIp);
		return issueTokens(user);
	}

	public AccessTokenResponse refresh(RefreshRequest request) {
		Long userId = Long.valueOf(jwtService.parseRefreshToken(request.refreshToken()).getSubject());
		AppUser user = appUserRepository.findById(userId)
				.orElseThrow(() -> new InvalidTokenException("존재하지 않는 사용자입니다."));
		if (user.isDisabled()) {
			throw new InvalidTokenException("사용할 수 없는 계정입니다.");
		}
		return new AccessTokenResponse(jwtService.generateAccessToken(user.getId(), user.getName(), user.getRole()));
	}

	/** 계정+IP 키를 먼저 차감하고(초과면 429, 아무것도 안 셈), 이어서 계정 전체 키를 차감한다(초과면 앞의 차감을 되돌리고 429). */
	private void acquireAttempt(String schoolKey, String nameKey, String clientIp) {
		String ipKey = ipThrottleKey(schoolKey, nameKey, clientIp);
		attemptLimiter.tryAcquire(ipKey);
		try {
			attemptLimiter.tryAcquire(throttleKey(schoolKey, nameKey), accountMaxAttempts);
		} catch (TooManyAttemptsException e) {
			attemptLimiter.release(ipKey);
			throw e;
		}
	}

	/** 로그인 성공 시 두 키에서 자기 차감 1회씩만 돌려준다. */
	private void releaseAttempt(String schoolKey, String nameKey, String clientIp) {
		attemptLimiter.release(ipThrottleKey(schoolKey, nameKey, clientIp));
		attemptLimiter.release(throttleKey(schoolKey, nameKey));
	}

	private static String ipThrottleKey(String schoolKey, String nameKey, String clientIp) {
		// 학교·이름 키 길이를 앞에 붙여 필드 경계가 모호하지 않게 한다 (IP가 맨 뒤).
		return IP_THROTTLE_PREFIX + schoolKey.length() + ":" + schoolKey + ":" + nameKey.length() + ":" + nameKey
				+ ":" + clientIp;
	}

	private static String throttleKey(String schoolKey, String nameKey) {
		// 학교 키 길이를 앞에 붙여 ('a:b','c')와 ('a','b:c')가 같은 키가 되지 않게 한다 (이름 키가 맨 뒤라 나머지는 모호하지 않다).
		return THROTTLE_PREFIX + schoolKey.length() + ":" + schoolKey + ":" + nameKey;
	}

	/** 같은 (학교, 이름)의 학생·교사 계정. 교사를 먼저 맞춰 보도록 앞에 둔다. */
	private List<AppUser> findCandidates(String schoolKey, String nameKey) {
		return appUserRepository.findAllBySchoolKeyAndNameKeyAndRoleIn(
						schoolKey, nameKey, List.of(AppUser.STUDENT, AppUser.TEACHER))
				.stream()
				.sorted(Comparator.comparingInt(user -> AppUser.TEACHER.equals(user.getRole()) ? 0 : 1))
				.toList();
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
