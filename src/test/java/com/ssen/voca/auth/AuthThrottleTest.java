package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

// 제한기는 @Transactional로 초기화되지 않는 공유 싱글턴이므로 테스트마다 서로 다른 학교와 이름을 쓴다.
@SpringBootTest
@Import(AuthThrottleTest.ClockConfig.class)
@Transactional
class AuthThrottleTest {

	private static final String IP = "127.0.0.1";

	private String school;

	@BeforeEach
	void newSchool() {
		school = "제한학교-" + UUID.randomUUID();
	}

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
			assertThatThrownBy(() -> authService.login(new LoginRequest(school, name, "0000"), IP))
					.isInstanceOf(InvalidCredentialsException.class);
		}
	}

	@Test
	void sixthAttemptIsRejectedEvenWithCorrectPin() {
		authService.signup(new SignupRequest(school, "제한첫째", "1234"), IP);
		failLogin("제한첫째", 4);

		assertThatThrownBy(() -> authService.login(new LoginRequest(school, "제한첫째", "1234"), IP))
				.isInstanceOf(TooManyAttemptsException.class);
		assertThatThrownBy(() -> authService.signup(new SignupRequest(school, "제한첫째", "5678"), IP))
				.isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void otherNameIsUnaffected() {
		failLogin("제한둘째", 5);

		assertThatThrownBy(() -> authService.login(new LoginRequest(school, "제한둘째", "0000"), IP))
				.isInstanceOf(TooManyAttemptsException.class);
		failLogin("제한다른이름", 1);
	}

	@Test
	void windowExpiryResetsCounter() {
		authService.signup(new SignupRequest(school, "제한셋째", "1234"), IP);
		failLogin("제한셋째", 4);
		assertThatThrownBy(() -> authService.login(new LoginRequest(school, "제한셋째", "1234"), IP))
				.isInstanceOf(TooManyAttemptsException.class);

		clock.advanceMillis(300_000);

		assertThat(authService.login(new LoginRequest(school, "제한셋째", "1234"), IP).accessToken()).isNotEmpty();
	}

	@Test
	void successfulLoginRefundsExactlyOne() {
		authService.signup(new SignupRequest(school, "제한넷째", "1234"), IP);
		for (int i = 0; i < 3; i++) {
			authService.login(new LoginRequest(school, "제한넷째", "1234"), IP);
		}

		// 성공한 로그인은 순수 0회 소모이므로 카운터는 가입 1회뿐이다. 실패 4번을 채우면 막힌다.
		failLogin("제한넷째", 4);
		assertThatThrownBy(() -> authService.login(new LoginRequest(school, "제한넷째", "1234"), IP))
				.isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void owningAnAccountUnderTheVictimNameDoesNotResetTheCounter() {
		// 공격자가 피해자와 같은 이름으로 자기 PIN 계정을 만든 뒤, 자기 계정 로그인으로 카운터를 비우려는 시도.
		authService.signup(new SignupRequest(school, "제한방어", "1111"), IP);
		for (int guess = 0; guess < 4; guess++) {
			failLogin("제한방어", 1);
			if (guess < 3) {
				authService.login(new LoginRequest(school, "제한방어", "1111"), IP);
			}
		}

		// 가입 1 + 실패 추측 4 = 5회. 자기 로그인 성공을 아무리 끼워도 5번째 추측부터는 429.
		assertThatThrownBy(() -> authService.login(new LoginRequest(school, "제한방어", "0000"), IP))
				.isInstanceOf(TooManyAttemptsException.class);
		assertThatThrownBy(() -> authService.login(new LoginRequest(school, "제한방어", "1111"), IP))
				.isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void signupCallsCountTowardTheSameCounter() {
		for (int pin = 1000; pin < 1005; pin++) {
			authService.signup(new SignupRequest(school, "제한다섯째", String.valueOf(pin)), IP);
		}

		assertThatThrownBy(() -> authService.signup(new SignupRequest(school, "제한다섯째", "2000"), IP))
				.isInstanceOf(TooManyAttemptsException.class);
		assertThatThrownBy(() -> authService.login(new LoginRequest(school, "제한다섯째", "1000"), IP))
				.isInstanceOf(TooManyAttemptsException.class);
	}
}
