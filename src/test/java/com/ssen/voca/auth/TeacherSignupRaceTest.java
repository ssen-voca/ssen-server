package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import com.ssen.voca.auth.dto.TeacherSignupRequest;
import com.ssen.voca.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

// 동시 가입 경합을 흉내 낸다: existsByEmail은 false지만 행은 이미 있어 유니크 인덱스가 막는다.
@SpringBootTest
@Transactional
class TeacherSignupRaceTest {

	@Autowired
	private TeacherAuthService teacherAuthService;

	@MockitoSpyBean
	private AppUserRepository appUserRepository;

	@Value("${app.teacher.invite-code}")
	private String inviteCode;

	@Test
	void racingDuplicateSignupBecomesEmailExistsNotServerError() {
		teacherAuthService.signup(new TeacherSignupRequest("경합교사", "race@example.com", "password1", inviteCode));
		doReturn(false).when(appUserRepository).existsByEmail(anyString());

		assertThatThrownBy(() -> teacherAuthService.signup(
				new TeacherSignupRequest("경합교사둘", "race@example.com", "password1", inviteCode)))
				.isInstanceOf(TeacherEmailExistsException.class);
	}
}
