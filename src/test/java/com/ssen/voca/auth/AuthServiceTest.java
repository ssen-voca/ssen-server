package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.RefreshRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import com.ssen.voca.support.ClassroomFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AuthServiceTest {

	@Autowired
	private ClassroomFixture fixture;

	private String code;

	@BeforeEach
	void newClassroom() {
		code = fixture.newClassroom().getCode();
	}

	@Autowired
	private AuthService authService;

	@Autowired
	private JwtService jwtService;

	@Test
	void signupCreatesUserAndReturnsTokens() {
		TokenResponse tokens = authService.signup(new SignupRequest(code, "서비스가입", "1234"));

		assertThat(jwtService.parseAccessToken(tokens.accessToken()).get("name")).isEqualTo("서비스가입");
		assertThat(jwtService.parseRefreshToken(tokens.refreshToken())).isNotNull();
	}

	@Test
	void signupWithSameNameAndPinThrows() {
		authService.signup(new SignupRequest(code, "서비스중복", "1234"));

		assertThatThrownBy(() -> authService.signup(new SignupRequest(code, "서비스중복", "1234")))
				.isInstanceOf(StudentAlreadyExistsException.class);
	}

	@Test
	void signupWithSameNameAndDifferentPinCreatesSeparateAccounts() {
		TokenResponse first = authService.signup(new SignupRequest(code, "서비스동명", "1111"));
		TokenResponse second = authService.signup(new SignupRequest(code, "서비스동명", "2222"));

		assertThat(jwtService.parseAccessToken(first.accessToken()).getSubject())
				.isNotEqualTo(jwtService.parseAccessToken(second.accessToken()).getSubject());
	}

	@Test
	void signupNormalizesNameWhitespaceAndCase() {
		authService.signup(new SignupRequest(code, " 서비스  민준 ", "1234"));
		authService.signup(new SignupRequest(code, "Service  Kim", "1234"));

		assertThatThrownBy(() -> authService.signup(new SignupRequest(code, "서비스 민준", "1234")))
				.isInstanceOf(StudentAlreadyExistsException.class);
		assertThatThrownBy(() -> authService.signup(new SignupRequest(code, "  sERVICE kIM ", "1234")))
				.isInstanceOf(StudentAlreadyExistsException.class);
	}

	@Test
	void loginWithCorrectPinReturnsTokens() {
		authService.signup(new SignupRequest(code, "서비스로그인", "1234"));

		TokenResponse tokens = authService.login(new LoginRequest(code, "서비스로그인", "1234"));

		assertThat(jwtService.parseAccessToken(tokens.accessToken())).isNotNull();
	}

	@Test
	void loginWithWrongPinThrows() {
		authService.signup(new SignupRequest(code, "서비스오답", "1234"));

		assertThatThrownBy(() -> authService.login(new LoginRequest(code, "서비스오답", "9999")))
				.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void loginWithUnknownNameThrows() {
		assertThatThrownBy(() -> authService.login(new LoginRequest(code, "서비스없는사람", "1234")))
				.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void loginPicksTheAccountWhoseHashMatches() {
		TokenResponse first = authService.signup(new SignupRequest(code, "서비스동명로그인", "1111"));
		TokenResponse second = authService.signup(new SignupRequest(code, "서비스동명로그인", "2222"));

		String secondLogin = authService.login(new LoginRequest(code, "서비스동명로그인", "2222")).accessToken();

		assertThat(jwtService.parseAccessToken(secondLogin).getSubject())
				.isEqualTo(jwtService.parseAccessToken(second.accessToken()).getSubject())
				.isNotEqualTo(jwtService.parseAccessToken(first.accessToken()).getSubject());
	}

	@Test
	void refreshWithValidRefreshTokenReturnsNewAccessToken() {
		TokenResponse tokens = authService.signup(new SignupRequest(code, "서비스갱신", "1234"));

		var accessTokenResponse = authService.refresh(new RefreshRequest(tokens.refreshToken()));

		assertThat(jwtService.parseAccessToken(accessTokenResponse.accessToken()).get("name"))
				.isEqualTo("서비스갱신");
	}

	@Test
	void refreshWithAccessTokenThrows() {
		TokenResponse tokens = authService.signup(new SignupRequest(code, "서비스갱신오류", "1234"));

		assertThatThrownBy(() -> authService.refresh(new RefreshRequest(tokens.accessToken())))
				.isInstanceOf(InvalidTokenException.class);
	}
}
