package com.ssen.voca.user;

import com.ssen.voca.auth.InvalidTokenException;
import com.ssen.voca.auth.LoginAttemptLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WithdrawalService {

	private static final String THROTTLE_PREFIX = "withdraw:";

	private final AppUserRepository appUserRepository;
	private final PasswordEncoder passwordEncoder;
	private final LoginAttemptLimiter attemptLimiter;

	public WithdrawalService(
			AppUserRepository appUserRepository, PasswordEncoder passwordEncoder, LoginAttemptLimiter attemptLimiter) {
		this.appUserRepository = appUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.attemptLimiter = attemptLimiter;
	}

	/**
	 * 번호 4자리를 다시 확인한 뒤 학생 계정을 지운다. 북마크와 학생의 개인 단어는 DB의 ON DELETE CASCADE로 함께 지워지고,
	 * 교사가 올린 수업 단어(owner 없음)는 그대로다. 번호 확인은 로그인과 같은 방식으로 시도 횟수를 제한한다.
	 */
	@Transactional
	public void withdraw(Long userId, String pin) {
		// 훔친 토큰으로 번호를 맞혀 보는 것을 막으려고 먼저 1회 차감한다. 성공하면 계정이 사라지므로 돌려줄 것이 없다.
		attemptLimiter.tryAcquire(THROTTLE_PREFIX + userId);
		AppUser user = appUserRepository.findById(userId)
				.orElseThrow(() -> new InvalidTokenException("존재하지 않는 사용자입니다."));
		if (!AppUser.STUDENT.equals(user.getRole())) {
			// 교사 계정은 서버 설정으로 관리한다.
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "교사 계정은 탈퇴할 수 없어요.");
		}
		// 401은 앱이 로그인 만료로 보고 토큰 갱신을 시도하므로 번호 오류는 403으로 답한다.
		if (!passwordEncoder.matches(pin, user.getSecretHash())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "번호가 올바르지 않아요.");
		}
		appUserRepository.delete(user);
	}
}
