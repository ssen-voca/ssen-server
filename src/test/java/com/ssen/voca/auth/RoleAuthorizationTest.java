package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

// /api/teacher/anything 에는 핸들러가 없다. 권한을 통과하면 404, 못 통과하면 401/403이므로 권한만 따로 검증할 수 있다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RoleAuthorizationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Value("${app.jwt.secret}")
	private String secret;

	private ResultActions getTeacherPath(String token) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/teacher/anything");
		if (token != null) {
			request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		return mockMvc.perform(request);
	}

	/** role claim이 없던 시절의 액세스 토큰. */
	private String legacyToken() {
		Date now = new Date();
		return Jwts.builder()
				.subject("1")
				.claim("name", "옛토큰")
				.issuedAt(now)
				.expiration(new Date(now.getTime() + 60_000))
				.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
				.compact();
	}

	@Test
	void noTokenIsUnauthorized() throws Exception {
		getTeacherPath(null).andExpect(status().isUnauthorized());
	}

	@Test
	void studentTokenIsForbidden() throws Exception {
		getTeacherPath(jwtService.generateAccessToken(1L, "학생", AppUser.STUDENT))
				.andExpect(status().isForbidden());
	}

	@Test
	void teacherTokenPassesAuthorization() throws Exception {
		getTeacherPath(jwtService.generateAccessToken(1L, "교사", AppUser.TEACHER))
				.andExpect(status().isNotFound());
	}

	@Test
	void tokenWithoutRoleClaimIsTreatedAsStudent() throws Exception {
		getTeacherPath(legacyToken()).andExpect(status().isForbidden());
	}

	@Test
	void refreshKeepsTeacherRole() throws Exception {
		AppUser teacher = appUserRepository.save(
				AppUser.teacher("교사리프", "교사리프", "refresh-teacher@example.com", "hash"));
		String refreshToken = jwtService.generateRefreshToken(teacher.getId());

		String body = mockMvc.perform(post("/api/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String accessToken = objectMapper.readTree(body).get("accessToken").asText();

		assertThat(jwtService.parseAccessToken(accessToken).get(JwtService.ROLE_CLAIM, String.class))
				.isEqualTo(AppUser.TEACHER);
		getTeacherPath(accessToken).andExpect(status().isNotFound());
	}
}
