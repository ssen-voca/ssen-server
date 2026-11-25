package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

// 계정+IP 키(5회)와 계정 전체 상한(여기서는 8회)의 두 겹 제한. 제한기는 공유 싱글턴이므로 테스트마다 새 학교를 쓴다.
@SpringBootTest(properties = {"app.auth.account-max-attempts=8", "app.auth.client-ip-header=X-Forwarded-For"})
@AutoConfigureMockMvc
@Import(AuthIpThrottleTest.ClockConfig.class)
@Transactional
class AuthIpThrottleTest {

	private static final String PIN = "2468";

	private String school;

	@BeforeEach
	void newSchoolWithATeacher() {
		school = "아이피학원-" + UUID.randomUUID();
		seeder.seed(List.of(new TeacherAccounts.Entry(school, "김선생", PIN)));
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
	private TeacherAccountSeeder seeder;

	@Autowired
	private MutableClock clock;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private void failLogin(String school, String name, String ip, int times) {
		for (int i = 0; i < times; i++) {
			assertThatThrownBy(() -> authService.login(new LoginRequest(school, name, "0000"), ip))
					.isInstanceOf(InvalidCredentialsException.class);
		}
	}

	private void assertLocked(String school, String name, String pin, String ip) {
		assertThatThrownBy(() -> authService.login(new LoginRequest(school, name, pin), ip))
				.isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void sameIpLocksAfterFiveFailuresEvenWithTheCorrectPin() {
		failLogin(school, "김선생", "10.0.0.1", 5);

		assertLocked(school, "김선생", PIN, "10.0.0.1");
	}

	@Test
	void aLockedOutIpDoesNotLockTheTeacherOutFromAnotherIp() {
		failLogin(school, "김선생", "10.0.0.1", 5);
		assertLocked(school, "김선생", PIN, "10.0.0.1");

		assertThat(authService.login(new LoginRequest(school, "김선생", PIN), "10.0.0.2").accessToken()).isNotEmpty();
	}

	@Test
	void manyIpsFailingPastTheAccountCapLockEveryIpIncludingNewOnes() {
		failLogin(school, "김선생", "10.0.0.1", 5);
		failLogin(school, "김선생", "10.0.0.2", 3);

		assertLocked(school, "김선생", PIN, "10.0.0.3");
		assertLocked(school, "김선생", PIN, "10.0.0.2");
	}

	@Test
	void attemptsRejectedByTheAccountCapDoNotCountAgainstTheIp() {
		failLogin(school, "김선생", "10.0.0.1", 5);
		failLogin(school, "김선생", "10.0.0.2", 3);
		clock.advanceMillis(200_000);
		// 계정 전체 상한에서 거절되는 시도를 IP-3에서 여러 번 보낸다. 세어졌다면 IP-3 키가 이 창에 5회로 차 있을 것이다.
		for (int i = 0; i < 6; i++) {
			assertLocked(school, "김선생", PIN, "10.0.0.3");
		}
		clock.advanceMillis(100_000);

		// 계정 창(처음 시도 기준 5분)은 끝났고 IP-3은 한 번도 세어지지 않았으므로 바로 로그인된다.
		assertThat(authService.login(new LoginRequest(school, "김선생", PIN), "10.0.0.3").accessToken()).isNotEmpty();
	}

	@Test
	void successfulLoginsRefundBothKeys() {
		// 5회(IP)와 8회(계정 전체)를 모두 넘는 성공이 이어져도 막히지 않는다.
		for (int i = 0; i < 10; i++) {
			assertThat(authService.login(new LoginRequest(school, "김선생", PIN), "10.0.0.1").accessToken()).isNotEmpty();
		}

		failLogin(school, "김선생", "10.0.0.1", 5);
		assertLocked(school, "김선생", PIN, "10.0.0.1");
	}

	@Test
	void signupFollowsTheSameTwoLayerRule() {
		for (int pin = 1000; pin < 1005; pin++) {
			authService.signup(new SignupRequest(school, "가입학생", String.valueOf(pin)), "10.0.0.1");
		}
		assertThatThrownBy(() -> authService.signup(new SignupRequest(school, "가입학생", "2000"), "10.0.0.1"))
				.isInstanceOf(TooManyAttemptsException.class);
		// 다른 IP의 가입은 되고, 계정 전체 상한(8회)을 채우면 새 IP도 막힌다.
		for (int pin = 1005; pin < 1008; pin++) {
			authService.signup(new SignupRequest(school, "가입학생", String.valueOf(pin)), "10.0.0.2");
		}

		assertThatThrownBy(() -> authService.signup(new SignupRequest(school, "가입학생", "2001"), "10.0.0.3"))
				.isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void sameNameInAnotherSchoolIsIndependent() {
		String other = school + "-다른학교";
		failLogin(school, "김선생", "10.0.0.1", 5);
		failLogin(school, "김선생", "10.0.0.2", 3);
		assertLocked(school, "김선생", PIN, "10.0.0.3");

		failLogin(other, "김선생", "10.0.0.1", 5);
		assertLocked(other, "김선생", "0000", "10.0.0.1");
		failLogin(other, "김선생", "10.0.0.2", 1);
	}

	private ResultActions login(String pin, String forwardedFor, String remoteAddr) throws Exception {
		var request = post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("school", school, "name", "김선생", "phoneLast4", pin)))
				.with(r -> {
					r.setRemoteAddr(remoteAddr);
					return r;
				});
		if (forwardedFor != null) {
			request.header("X-Forwarded-For", forwardedFor);
		}
		return mockMvc.perform(request);
	}

	@Test
	void httpLayerUsesTheLastForwardedValueAndIgnoresForgedLeadingOnes() throws Exception {
		// 앞쪽 값을 바꿔 가며 틀려도 마지막 값(프록시가 붙인 값)이 같으면 같은 IP로 센다.
		for (int i = 0; i < 5; i++) {
			login("0000", "6.6.6." + i + ", 9.9.9.9", "10.0.0.1").andExpect(status().isUnauthorized());
		}
		login(PIN, "5.5.5.5, 9.9.9.9", "10.0.0.1").andExpect(status().isTooManyRequests());

		// 마지막 값이 다르면 다른 IP다.
		login(PIN, "9.9.9.9, 8.8.8.8", "10.0.0.1").andExpect(status().isOk());
	}

	@Test
	void httpLayerFallsBackToTheConnectionAddressWithoutTheHeader() throws Exception {
		for (int i = 0; i < 5; i++) {
			login("0000", null, "10.0.0.5").andExpect(status().isUnauthorized());
		}

		login(PIN, null, "10.0.0.5").andExpect(status().isTooManyRequests());
		login(PIN, null, "10.0.0.6").andExpect(status().isOk());
	}
}
