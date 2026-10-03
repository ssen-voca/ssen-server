package com.ssen.voca.auth;

import com.ssen.voca.auth.dto.TeacherLoginRequest;
import com.ssen.voca.auth.dto.TeacherSignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherAuthService {

	private static final String BAD_CREDENTIALS = "이메일 또는 비밀번호가 올바르지 않아요.";
	// ponytail: dummy BCrypt hash so an unknown email still pays the encoder cost,
	// keeping login timing similar regardless of account existence.
	private static final String DUMMY_SECRET_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
	private static final String INVITE_THROTTLE_KEY = "teacher-signup";
	private static final int MAX_PASSWORD_BYTES = 72;
	private static final int MAX_EMAIL_LENGTH = 255;

	private final AppUserRepository appUserRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final LoginAttemptLimiter attemptLimiter;
	private final byte[] inviteCode;

	public TeacherAuthService(
			AppUserRepository appUserRepository,
			PasswordEncoder passwordEncoder,
			JwtService jwtService,
			LoginAttemptLimiter attemptLimiter,
			@Value("${app.teacher.invite-code}") String inviteCode) {
		this.appUserRepository = appUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.attemptLimiter = attemptLimiter;
		String code = inviteCode.strip();
		if (code.isEmpty()) {
			throw new IllegalStateException("app.teacher.invite-code must not be blank");
		}
		this.inviteCode = code.getBytes(StandardCharsets.UTF_8);
	}

	@Transactional
	public TokenResponse signup(TeacherSignupRequest request) {
		if (tooLong(request.password())) {
			throw new InvalidPasswordException();
		}
		String nameKey = Names.key(request.name());
		// 소문자화로 길어질 수 있으므로 정규화 뒤에 검사한다. 가입 코드와 무관하므로 선차감 전에 거절해도 된다.
		String email = normalizeEmail(request.email());
		if (email.length() > MAX_EMAIL_LENGTH) {
			throw new InvalidEmailException();
		}
		// 가입 코드 대입을 막기 위해 코드 검사 전에 선차감하고, 코드가 맞으면 1회를 돌려준다.
		attemptLimiter.tryAcquire(INVITE_THROTTLE_KEY);
		if (!MessageDigest.isEqual(inviteCode, request.inviteCode().getBytes(StandardCharsets.UTF_8))) {
			throw new InvalidInviteCodeException();
		}
		attemptLimiter.release(INVITE_THROTTLE_KEY);

		if (appUserRepository.existsByEmail(email)) {
			throw new TeacherEmailExistsException();
		}
		AppUser teacher = AppUser.teacher(
				Names.display(request.name()), nameKey, email, passwordEncoder.encode(request.password()));
		try {
			// 동시 가입 경합은 uq_app_user_email 유니크 인덱스가 막는다.
			appUserRepository.saveAndFlush(teacher);
		} catch (DataIntegrityViolationException e) {
			throw new TeacherEmailExistsException();
		}
		return issueTokens(teacher);
	}

	public TokenResponse login(TeacherLoginRequest request) {
		String email = normalizeEmail(request.email());
		// 소문자화로 길어질 수 있다. 이런 이메일의 계정은 없으므로 제한 맵에 키를 넣지 않고 바로 거절한다.
		if (email.length() > MAX_EMAIL_LENGTH) {
			throw new InvalidCredentialsException(BAD_CREDENTIALS);
		}
		String key = "teacher:" + email;
		attemptLimiter.tryAcquire(key);
		if (tooLong(request.password())) {
			throw new InvalidCredentialsException(BAD_CREDENTIALS);
		}
		AppUser teacher = appUserRepository.findByEmail(email)
				.filter(user -> AppUser.TEACHER.equals(user.getRole()))
				.orElse(null);
		String hash = teacher != null ? teacher.getSecretHash() : DUMMY_SECRET_HASH;
		boolean matches = passwordEncoder.matches(request.password(), hash);
		if (teacher == null || !matches) {
			throw new InvalidCredentialsException(BAD_CREDENTIALS);
		}
		attemptLimiter.release(key);
		return issueTokens(teacher);
	}

	private static boolean tooLong(String password) {
		return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
	}

	private static String normalizeEmail(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	private TokenResponse issueTokens(AppUser user) {
		return new TokenResponse(
				jwtService.generateAccessToken(user.getId(), user.getName(), user.getRole()),
				jwtService.generateRefreshToken(user.getId()));
	}
}
