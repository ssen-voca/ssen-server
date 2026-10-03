package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssen.voca.auth.dto.TeacherLoginRequest;
import com.ssen.voca.auth.dto.TeacherSignupRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

// 전용 시계(별도 Spring 컨텍스트)를 쓰므로 다른 테스트의 시도 횟수와 섞이지 않는다.
@SpringBootTest
@Import(TeacherThrottleTest.ClockConfig.class)
@Transactional
class TeacherThrottleTest {

	@TestConfiguration
	static class ClockConfig {
		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock();
		}
	}

	@Autowired
	private TeacherAuthService teacherAuthService;

	@Autowired
	private MutableClock clock;

	@Value("${app.teacher.invite-code}")
	private String inviteCode;

	@BeforeEach
	void openFreshWindow() {
		clock.advanceMillis(6 * 60 * 1000);
	}

	private void signupWithCode(String email, String code) {
		teacherAuthService.signup(new TeacherSignupRequest("제한교사", email, "password1", code));
	}

	@Test
	void sixthSignupAttemptIsRejectedEvenWithCorrectCode() {
		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> signupWithCode("throttle-a@example.com", "wrong-code"))
					.isInstanceOf(InvalidInviteCodeException.class);
		}

		assertThatThrownBy(() -> signupWithCode("throttle-a@example.com", inviteCode))
				.isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void successfulSignupsDoNotConsumeTheBudget() {
		for (int i = 0; i < 8; i++) {
			int n = i;
			assertThatCode(() -> signupWithCode("throttle-ok-" + n + "@example.com", inviteCode))
					.doesNotThrowAnyException();
		}
	}

	@Test
	void signupBudgetResetsAfterTheWindow() {
		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> signupWithCode("throttle-b@example.com", "wrong-code"))
					.isInstanceOf(InvalidInviteCodeException.class);
		}
		clock.advanceMillis(5 * 60 * 1000);

		assertThatCode(() -> signupWithCode("throttle-b@example.com", inviteCode)).doesNotThrowAnyException();
	}

	@Test
	void sixthFailedLoginIsRejectedEvenWithCorrectPassword() {
		signupWithCode("throttle-login@example.com", inviteCode);
		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> teacherAuthService.login(
					new TeacherLoginRequest("throttle-login@example.com", "wrong-password")))
					.isInstanceOf(InvalidCredentialsException.class);
		}

		assertThatThrownBy(() -> teacherAuthService.login(
				new TeacherLoginRequest("throttle-login@example.com", "password1")))
				.isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void otherEmailIsUnaffectedByAnotherEmailsFailures() {
		signupWithCode("throttle-c@example.com", inviteCode);
		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> teacherAuthService.login(
					new TeacherLoginRequest("throttle-d@example.com", "wrong-password")))
					.isInstanceOf(InvalidCredentialsException.class);
		}

		assertThatCode(() -> teacherAuthService.login(new TeacherLoginRequest("throttle-c@example.com", "password1")))
				.doesNotThrowAnyException();
	}
}
