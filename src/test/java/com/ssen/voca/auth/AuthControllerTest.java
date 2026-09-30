package com.ssen.voca.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.RefreshRequest;
import com.ssen.voca.auth.dto.SignupRequest;
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
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void signupReturns201WithTokens() throws Exception {
		mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new SignupRequest("김학생", "controller-signup@example.com", "password123"))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test
	void signupWithDuplicateEmailReturns409() throws Exception {
		SignupRequest request = new SignupRequest("김학생", "dup-controller@example.com", "password123");

		mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)));

		mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new SignupRequest("이학생", "dup-controller@example.com", "password456"))))
				.andExpect(status().isConflict());
	}

	@Test
	void loginWithCorrectPasswordReturns200WithTokens() throws Exception {
		mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
						new SignupRequest("김학생", "login-controller@example.com", "password123"))));

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new LoginRequest("login-controller@example.com", "password123"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test
	void loginWithWrongPasswordReturns401() throws Exception {
		mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
						new SignupRequest("김학생", "wrongpw-controller@example.com", "password123"))));

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new LoginRequest("wrongpw-controller@example.com", "wrong-password"))))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void refreshWithRefreshTokenReturns200WithNewAccessToken() throws Exception {
		MvcResult signupResult = mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new SignupRequest("김학생", "refresh-controller@example.com", "password123"))))
				.andReturn();
		String refreshToken = objectMapper.readTree(signupResult.getResponse().getContentAsString())
				.get("refreshToken").asText();

		mockMvc.perform(post("/api/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test
	void refreshWithAccessTokenReturns401() throws Exception {
		MvcResult signupResult = mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new SignupRequest("김학생", "badrefresh-controller@example.com", "password123"))))
				.andReturn();
		String accessToken = objectMapper.readTree(signupResult.getResponse().getContentAsString())
				.get("accessToken").asText();

		mockMvc.perform(post("/api/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshRequest(accessToken))))
				.andExpect(status().isUnauthorized());
	}
}
