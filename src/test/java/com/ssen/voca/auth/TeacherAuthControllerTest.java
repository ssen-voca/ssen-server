package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

// 가입 코드 시도 제한은 공유 싱글턴이다. 잘못된 코드로 가입을 시도하는 테스트는 이 클래스에서 1번만 쓰고,
// 한도 자체는 TeacherThrottleTest(전용 시계)에서 검증한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TeacherAuthControllerTest {

	private static final String BAD_INVITE = "교사 가입 코드가 올바르지 않아요.";
	private static final String EMAIL_EXISTS = "이미 가입된 이메일이에요. 로그인해 주세요.";
	private static final String BAD_CREDENTIALS = "이메일 또는 비밀번호가 올바르지 않아요.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JwtService jwtService;

	@Value("${app.teacher.invite-code}")
	private String inviteCode;

	private ResultActions postJson(String path, Map<String, String> body) throws Exception {
		return mockMvc.perform(MockMvcRequestBuilders.post(path)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)));
	}

	private ResultActions signup(String name, String email, String password, String code) throws Exception {
		return postJson("/api/teacher/signup",
				Map.of("name", name, "email", email, "password", password, "inviteCode", code));
	}

	private ResultActions login(String email, String password) throws Exception {
		return postJson("/api/teacher/login", Map.of("email", email, "password", password));
	}

	@Test
	void signupReturns201WithTokensAndTeacherRole() throws Exception {
		String body = signup("가입교사", "ctl-teacher@example.com", "password1", inviteCode)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andReturn().getResponse().getContentAsString();

		String accessToken = objectMapper.readTree(body).get("accessToken").asText();
		assertThat(jwtService.parseAccessToken(accessToken).get(JwtService.ROLE_CLAIM, String.class))
				.isEqualTo("TEACHER");
	}

	@Test
	void signupWithWrongInviteCodeReturns403() throws Exception {
		signup("틀린코드교사", "wrong-code@example.com", "password1", "not-the-code")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(BAD_INVITE));
	}

	@Test
	void duplicateEmailReturns409IgnoringCaseAndPadding() throws Exception {
		signup("중복교사", "Dup.Teacher@Example.com", "password1", inviteCode).andExpect(status().isCreated());

		signup("중복교사둘", " dup.teacher@example.com ", "password1", inviteCode)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(EMAIL_EXISTS));
	}

	@Test
	void validationErrorsReturn400WithMessage() throws Exception {
		signup("　", "v1@example.com", "password1", inviteCode)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("이름을 입력해 주세요"));
		signup("검증교사", "not-an-email", "password1", inviteCode)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("올바른 이메일 형식이 아니에요"));
		signup("검증교사", "v3@example.com", "short", inviteCode)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("비밀번호는 8자 이상으로 입력해 주세요"));
		signup("검증교사", "v4@example.com", "password1", "")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("교사 가입 코드를 입력해 주세요"));
	}

	@Test
	void passwordOver72BytesIsRejectedNotTruncated() throws Exception {
		String koreanPassword = "가".repeat(25); // 75바이트

		signup("긴비번교사", "long-pw@example.com", koreanPassword, inviteCode)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("비밀번호가 너무 길어요. 한글 24자, 영문·숫자 72자 이내로 입력해 주세요."));
		login("long-pw@example.com", koreanPassword)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(BAD_CREDENTIALS));
	}

	@Test
	void loginSucceedsIgnoringEmailCaseAndPadding() throws Exception {
		signup("로그인교사", "Login.Teacher@Example.com", "password1", inviteCode).andExpect(status().isCreated());

		login(" login.teacher@EXAMPLE.com ", "password1")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty());
	}

	@Test
	void wrongPasswordAndUnknownEmailGetTheSame401() throws Exception {
		signup("실패교사", "fail-teacher@example.com", "password1", inviteCode).andExpect(status().isCreated());

		login("fail-teacher@example.com", "wrong-password")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(BAD_CREDENTIALS));
		login("nobody-teacher@example.com", "password1")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(BAD_CREDENTIALS));
	}

	@Test
	void teacherNameDoesNotAffectStudents() throws Exception {
		signup("동명교사", "same-name-teacher@example.com", "password1", inviteCode).andExpect(status().isCreated());

		// 같은 이름의 학생이 같은 이름 키로 가입·로그인해도 교사 행과 섞이지 않는다.
		postJson("/api/auth/signup", Map.of("name", "동명교사", "phoneLast4", "1234"))
				.andExpect(status().isCreated());
		postJson("/api/auth/login", Map.of("name", "동명교사", "phoneLast4", "1234"))
				.andExpect(status().isOk());
		postJson("/api/auth/login", Map.of("name", "동명교사", "phoneLast4", "9999"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void passwordOfExactly72BytesIsAccepted() throws Exception {
		signup("경계교사", "boundary-pw@example.com", "가".repeat(24), inviteCode) // 72바이트
				.andExpect(status().isCreated());
	}

	@Test
	void loginEmailOver255CharsReturns400() throws Exception {
		login("a".repeat(256), "password1")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("이메일은 255자 이하로 입력해 주세요"));
	}

	@Test
	void signupEmailThatGrowsPast255WhenLowerCasedReturns400() throws Exception {
		// 원문은 255자이지만 'İ'가 소문자화되면 2글자가 되어 256자가 된다.
		String email = "İ" + "a".repeat(63) + "@" + "a".repeat(60) + "." + "a".repeat(60) + "." + "a".repeat(60) + "." + "a".repeat(7);
		assertThat(email).hasSize(255);

		signup("긴이메일교사", email, "password1", inviteCode)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("이메일은 255자 이하로 입력해 주세요"));
	}

	@Test
	void nameMessageWinsWhenSeveralFieldsAreInvalid() throws Exception {
		signup("", "not-an-email", "password1", inviteCode)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("이름을 입력해 주세요"));
	}
}
