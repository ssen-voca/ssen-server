package com.ssen.voca.classroom;

import com.ssen.voca.auth.InvalidTokenException;
import com.ssen.voca.auth.LoginAttemptLimiter;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClassroomJoinService {

	private static final String THROTTLE_PREFIX = "join:";

	private final AppUserRepository appUserRepository;
	private final ClassroomRepository classroomRepository;
	private final LoginAttemptLimiter attemptLimiter;

	public ClassroomJoinService(
			AppUserRepository appUserRepository,
			ClassroomRepository classroomRepository,
			LoginAttemptLimiter attemptLimiter) {
		this.appUserRepository = appUserRepository;
		this.classroomRepository = classroomRepository;
		this.attemptLimiter = attemptLimiter;
	}

	/** 참여 코드로 수업에 들어간다. 이미 다른 수업이면 옮기고, 같은 수업이면 그대로 둔다. */
	@Transactional
	public Classroom join(Long userId, String rawCode) {
		// 코드 추측을 막으려고 형식이 틀린 코드까지 모두 먼저 1회 차감하고, 성공했을 때만 돌려준다.
		String key = THROTTLE_PREFIX + userId;
		attemptLimiter.tryAcquire(key);
		AppUser user = appUserRepository.findById(userId)
				.orElseThrow(() -> new InvalidTokenException("존재하지 않는 사용자입니다."));
		String code = ClassCodes.normalize(rawCode);
		// 없는 코드와 형식이 틀린 코드를 같은 응답으로 돌려 코드 존재 여부를 드러내지 않는다.
		Classroom classroom = code == null ? null : classroomRepository.findByCode(code).orElse(null);
		if (classroom == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "참여 코드를 확인해 주세요.");
		}
		user.joinClassroom(classroom.getId());
		attemptLimiter.release(key);
		return classroom;
	}

	/** 수업에서 나간다. 수업이 없어도 오류 없이 끝난다(여러 번 불러도 같다). 계정·북마크·개인 단어는 그대로다. */
	@Transactional
	public void leave(Long userId) {
		AppUser user = appUserRepository.findById(userId)
				.orElseThrow(() -> new InvalidTokenException("존재하지 않는 사용자입니다."));
		user.leaveClassroom();
	}
}
