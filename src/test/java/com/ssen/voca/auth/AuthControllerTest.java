package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssen.voca.auth.dto.RefreshRequest;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {

	private static final String ALREADY_EXISTS = "이미 가입된 학생이에요. 로그인해 주세요.";
	private static final String BAD_CREDENTIALS = "이름 또는 휴대폰 번호가 올바르지 않아요.";
	private static final String NAME_REQUIRED = "이름을 입력해 주세요";
	private static final String NAME_TOO_LONG = "이름은 50자 이하로 입력해 주세요";
	private static final String PIN_INVALID = "휴대폰 번호 뒤 4자리를 숫자로 입력해 주세요";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private ResultActions postJson(String path, String json) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions auth(String path, String name, String phoneLast4) throws Exception {
		return postJson(path, objectMapper.writeValueAsString(
				Map.of("name", name, "phoneLast4", phoneLast4)));
	}

	@Test
	void signupReturns201WithTokens() throws Exception {
		auth("/api/auth/signup", "컨트롤러가입", "1234")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty());
	}

	@Test
	void signupWithSameNameAndDifferentPinAllowed() throws Exception {
		auth("/api/auth/signup", "컨트롤러동명", "1111").andExpect(status().isCreated());
		auth("/api/auth/signup", "컨트롤러동명", "2222").andExpect(status().isCreated());
	}

	@Test
	void signupWithSameNameAndSamePinReturns409() throws Exception {
		auth("/api/auth/signup", "컨트롤러중복", "1234").andExpect(status().isCreated());

		auth("/api/auth/signup", "컨트롤러중복", "1234")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(ALREADY_EXISTS));
	}

	@Test
	void signupNormalizesWhitespaceAndCase() throws Exception {
		auth("/api/auth/signup", " 컨트롤러  민준 ", "1234").andExpect(status().isCreated());
		auth("/api/auth/signup", "컨트롤러 민준", "1234")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(ALREADY_EXISTS));

		auth("/api/auth/signup", "Controller Kim", "1234").andExpect(status().isCreated());
		auth("/api/auth/signup", "  cONTROLLER   kIM ", "1234")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(ALREADY_EXISTS));
	}

	@Test
	void signupValidationErrorsReturn400WithFirstMessage() throws Exception {
		auth("/api/auth/signup", "   ", "1234")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(NAME_REQUIRED));
		auth("/api/auth/signup", "가".repeat(51), "1234")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(NAME_TOO_LONG));
		auth("/api/auth/signup", "컨트롤러검증", "123")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(PIN_INVALID));
		auth("/api/auth/signup", "컨트롤러검증", "12345")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(PIN_INVALID));
		auth("/api/auth/signup", "컨트롤러검증", "12a4")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(PIN_INVALID));
		auth("/api/auth/signup", "컨트롤러검증", "")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(PIN_INVALID));
		postJson("/api/auth/signup", "{\"name\":\"컨트롤러검증\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(PIN_INVALID));
	}

	@Test
	void namesThatNormalizeToBlankReturn400() throws Exception {
		for (String name : new String[] {"\u3000", " ", "\u200B", "\u200B\u3000\uFEFF\u2060 \u200C\u200D"}) {
			auth("/api/auth/signup", name, "1234")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(NAME_REQUIRED));
			auth("/api/auth/login", name, "1234")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(NAME_REQUIRED));
		}
	}

	@Test
	void nameThatGrowsPastFiftyWhenLowerCasedReturns400() throws Exception {
		// U+0130(İ) 소문자화는 2글자가 되어 26자 → 52자
		String name = "\u0130".repeat(26);

		auth("/api/auth/signup", name, "1234")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(NAME_TOO_LONG));
		auth("/api/auth/login", name, "1234")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(NAME_TOO_LONG));
	}

	@Test
	void invisibleCharactersAreIgnoredForIdentityAndDisplayNameStaysNonEmpty() throws Exception {
		String access = tokenFrom(auth("/api/auth/signup", "\u200B컨트롤러투명\u3000", "1234"), "accessToken");
		auth("/api/auth/signup", "컨트롤러\u200B투명", "1234").andExpect(status().isConflict());

		mockMvc.perform(get("/api/users/me")
						.header("Authorization", "Bearer " + access))
				.andExpect(jsonPath("$.name").value("컨트롤러투명"));
	}

	@Test
	void blankAndTooLongNameReportsRequiredFirst() throws Exception {
		auth("/api/auth/signup", " ".repeat(51), "1234")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(NAME_REQUIRED));
	}

	@Test
	void validationReportsNameBeforePhone() throws Exception {
		auth("/api/auth/login", "", "abc")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(NAME_REQUIRED));
	}

	@Test
	void malformedJsonReturns400() throws Exception {
		postJson("/api/auth/signup", "{not json")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않아요."));
	}

	@Test
	void loginWithCorrectPinReturns200WithTokens() throws Exception {
		auth("/api/auth/signup", "컨트롤러로그인", "1234");

		auth("/api/auth/login", "컨트롤러로그인", "1234")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test
	void loginWithWrongPinReturns401() throws Exception {
		auth("/api/auth/signup", "컨트롤러오답", "1234");

		auth("/api/auth/login", "컨트롤러오답", "9999")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(BAD_CREDENTIALS));
	}

	@Test
	void loginWithUnknownNameReturns401WithSameMessage() throws Exception {
		auth("/api/auth/login", "컨트롤러없는사람", "1234")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(BAD_CREDENTIALS));
	}

	@Test
	void sameNameStudentsEachLoginToTheirOwnAccount() throws Exception {
		String first = tokenFrom(auth("/api/auth/signup", "컨트롤러동명로그인", "1111"), "accessToken");
		String second = tokenFrom(auth("/api/auth/signup", "컨트롤러동명로그인", "2222"), "accessToken");

		String firstLogin = tokenFrom(auth("/api/auth/login", "컨트롤러동명로그인", "1111"), "accessToken");
		String secondLogin = tokenFrom(auth("/api/auth/login", "컨트롤러동명로그인", "2222"), "accessToken");

		assertThat(subject(firstLogin)).isEqualTo(subject(first));
		assertThat(subject(secondLogin)).isEqualTo(subject(second));
		assertThat(subject(firstLogin)).isNotEqualTo(subject(secondLogin));
	}

	@Test
	void refreshWithRefreshTokenReturns200WithNewAccessToken() throws Exception {
		String refreshToken = tokenFrom(auth("/api/auth/signup", "컨트롤러갱신", "1234"), "refreshToken");

		postJson("/api/auth/refresh", objectMapper.writeValueAsString(new RefreshRequest(refreshToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test
	void refreshWithAccessTokenReturns401() throws Exception {
		String accessToken = tokenFrom(auth("/api/auth/signup", "컨트롤러갱신오류", "1234"), "accessToken");

		postJson("/api/auth/refresh", objectMapper.writeValueAsString(new RefreshRequest(accessToken)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void sixthLoginAfterFiveFailuresReturns429EvenWithCorrectPin() throws Exception {
		auth("/api/auth/signup", "컨트롤러제한", "1234");

		for (int i = 0; i < 4; i++) {
			auth("/api/auth/login", "컨트롤러제한", "0000").andExpect(status().isUnauthorized());
		}
		// 가입 1회 + 실패 4회 = 5회 → 다음 시도는 정답이어도 429
		auth("/api/auth/login", "컨트롤러제한", "1234")
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.message").value("시도 횟수를 초과했어요. 5분 뒤에 다시 시도해 주세요."));

		auth("/api/auth/login", "컨트롤러제한다른사람", "1234").andExpect(status().isUnauthorized());
	}

	private String tokenFrom(ResultActions actions, String field) throws Exception {
		MvcResult result = actions.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get(field).asText();
	}

	private String subject(String jwt) throws Exception {
		String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
		return objectMapper.readTree(payload).get("sub").asText();
	}
}
