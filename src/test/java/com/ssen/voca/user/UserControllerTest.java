package com.ssen.voca.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.user.dto.ClassCodeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private JsonNode signup(String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new SignupRequest(name, "1234"))))
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private String signupAndGetAccessToken(String name) throws Exception {
		return signup(name).get("accessToken").asText();
	}

	@Test
	void meWithoutTokenReturns401() throws Exception {
		mockMvc.perform(get("/api/users/me"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void meWithValidTokenReturns200WithUserInfo() throws Exception {
		String accessToken = signupAndGetAccessToken("유저조회");

		mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("유저조회"))
				.andExpect(jsonPath("$.email").doesNotExist());
	}

	@Test
	void meWithInvalidTokenReturns401() throws Exception {
		mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer not-a-real-token"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void meWithRefreshTokenAsBearerReturns401() throws Exception {
		String refreshToken = signup("유저리프레시").get("refreshToken").asText();

		mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + refreshToken))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void updateClassCodeThenMeReflectsIt() throws Exception {
		String accessToken = signupAndGetAccessToken("유저참여코드");

		mockMvc.perform(patch("/api/users/me/class-code")
						.header("Authorization", "Bearer " + accessToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ClassCodeRequest("WINTER-2026-A"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.classCode").value("WINTER-2026-A"));

		mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(jsonPath("$.classCode").value("WINTER-2026-A"));
	}

	private org.springframework.test.web.servlet.ResultActions patchClassCode(String accessToken, String classCode)
			throws Exception {
		return mockMvc.perform(patch("/api/users/me/class-code")
				.header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				// 요청 DTO는 생성 시 trim 하므로 클라이언트 쪽 공백 제거를 피하려고 원문 JSON으로 보낸다.
				.content(objectMapper.writeValueAsString(java.util.Map.of("classCode", classCode))));
	}

	@Test
	void classCodeOverFiftyCharsReturns400WithMessage() throws Exception {
		String accessToken = signupAndGetAccessToken("유저코드길이");

		patchClassCode(accessToken, "A".repeat(51))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("참여 코드는 50자 이하로 입력해 주세요"));
	}

	@Test
	void classCodeOfExactlyFiftyCharsIsAccepted() throws Exception {
		String accessToken = signupAndGetAccessToken("유저코드오십");

		patchClassCode(accessToken, "A".repeat(50))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.classCode").value("A".repeat(50)));
	}

	@Test
	void whitespaceOnlyClassCodeReturns400() throws Exception {
		String accessToken = signupAndGetAccessToken("유저코드공백");

		// 기본 @NotBlank 메시지(로케일에 따라 달라지므로 값은 비교하지 않는다)가 {"message"}로 내려온다.
		patchClassCode(accessToken, "   ")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not("참여 코드는 50자 이하로 입력해 주세요")));
	}

	@Test
	void classCodeIsStoredTrimmedAndLengthIsCheckedOnTrimmedValue() throws Exception {
		String accessToken = signupAndGetAccessToken("유저코드트림");

		patchClassCode(accessToken, "  " + "B".repeat(50) + "  ")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.classCode").value("B".repeat(50)));

		mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(jsonPath("$.classCode").value("B".repeat(50)));
	}
}
