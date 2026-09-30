package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(AuthThrottleTest.ClockConfig.class)
@Transactional
class AuthThrottleTest {

	@TestConfiguration
	static class ClockConfig {
		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock();
		}
	}

	@Autowired
	private AuthService authService;

	@Autowired
	private MutableClock clock;

	private void failLogin(String name, int times) {
		for (int i = 0; i < times; i++) {
			assertThatThrownBy(() -> authService.login(new LoginRequest(name, "0000")))
					.isInstanceOf(InvalidCredentialsException.class);
		}
	}

	@Test
	void sixthAttemptIsRejectedEvenWithCorrectPin() {
		authService.signup(new SignupRequest("제한첫째", "1234"));
		failLogin("제한첫째", 4);

		assertThatThrownBy(() -> authService.login(new LoginRequest("제한첫째", "1234")))
				.isInstanceOf(TooManyAttemptsException.class);
		assertThatThrownBy(() -> authService.signup(new SignupRequest("제한첫째", "5678")))
				.isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void otherNameIsUnaffected() {
		failLogin("제한둘째", 5);

		assertThatThrownBy(() -> authService.login(new LoginRequest("제한둘째", "0000")))
				.isInstanceOf(TooManyAttemptsException.class);
		failLogin("제한다른이름", 1);
	}

	@Test
	void windowExpiryResetsCounter() {
		authService.signup(new SignupRequest("제한셋째", "1234"));
		failLogin("제한셋째", 4);
		assertThatThrownBy(() -> authService.login(new LoginRequest("제한셋째", "1234")))
				.isInstanceOf(TooManyAttemptsException.class);

		clock.advanceMillis(300_000);

		assertThat(authService.login(new LoginRequest("제한셋째", "1234")).accessToken()).isNotEmpty();
	}

	@Test
	void successfulLoginClearsCounter() {
		authService.signup(new SignupRequest("제한넷째", "1234"));
		failLogin("제한넷째", 3);

		authService.login(new LoginRequest("제한넷째", "1234"));

		// 성공으로 카운터가 비워졌으므로 실패 5번을 다시 채워야 막힌다 (비우지 않았다면 2번째 실패에서 막힘).
		failLogin("제한넷째", 5);
		assertThatThrownBy(() -> authService.login(new LoginRequest("제한넷째", "1234")))
				.isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void signupCallsCountTowardTheSameCounter() {
		for (int pin = 1000; pin < 1005; pin++) {
			authService.signup(new SignupRequest("제한다섯째", String.valueOf(pin)));
		}

		assertThatThrownBy(() -> authService.signup(new SignupRequest("제한다섯째", "2000")))
				.isInstanceOf(TooManyAttemptsException.class);
		assertThatThrownBy(() -> authService.login(new LoginRequest("제한다섯째", "1000")))
				.isInstanceOf(TooManyAttemptsException.class);
	}
}
