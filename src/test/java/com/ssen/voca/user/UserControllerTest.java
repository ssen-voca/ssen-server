package com.ssen.voca.user;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.classroom.Classroom;
import com.ssen.voca.support.ClassroomFixture;
import com.ssen.voca.auth.JwtService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserControllerTest {

	@Autowired
	private ClassroomFixture fixture;

	private Classroom classroom;

	@BeforeEach
	void newClassroom() {
		classroom = fixture.newClassroom();
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtService jwtService;

	private JsonNode signup(String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new SignupRequest(classroom.getCode(), name, "1234"))))
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
				.andExpect(jsonPath("$.email").value(nullValue()));
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

	@Value("${app.teacher.invite-code}")
	private String inviteCode;

	@Test
	void teacherMeIncludesEmailAndRole() throws Exception {
		String response = mockMvc.perform(post("/api/teacher/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of(
								"name", "내정보교사", "email", "me-teacher@example.com",
								"password", "password1", "inviteCode", inviteCode))))
				.andReturn().getResponse().getContentAsString();
		String token = objectMapper.readTree(response).get("accessToken").asText();

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("TEACHER"))
				.andExpect(jsonPath("$.email").value("me-teacher@example.com"));
	}

	@Test
	void studentMeHasNullEmail() throws Exception {
		String token = signupAndGetAccessToken("내정보학생이메일");

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("STUDENT"))
				.andExpect(jsonPath("$.email").value(nullValue()));
	}

	@Test
	void studentMeShowsTheClassroom() throws Exception {
		String token = signupAndGetAccessToken("내정보수업");

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.classroom.id").value(classroom.getId()))
				.andExpect(jsonPath("$.classroom.name").value(classroom.getName()))
				.andExpect(jsonPath("$.classroom.code").value(classroom.getCode()))
				.andExpect(jsonPath("$.classCode").doesNotExist());
	}

	@Test
	void teacherMeHasNullClassroom() throws Exception {
		String response = mockMvc.perform(post("/api/teacher/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of(
								"name", "내정보교사수업", "email", "me-teacher-classroom@example.com",
								"password", "password1", "inviteCode", inviteCode))))
				.andReturn().getResponse().getContentAsString();
		String token = objectMapper.readTree(response).get("accessToken").asText();

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(jsonPath("$.classroom").value(nullValue()));
	}

	@Test
	void legacyStudentWithoutClassroomStillGetsMe() throws Exception {
		// 수업이 없던 시절의 학생 행(V6 이전). CHECK 제약이 NOT VALID라 기존 행은 남을 수 있고, 새 토큰은 직접 만든다.
		jdbcTemplate.execute("ALTER TABLE app_user DROP CONSTRAINT ck_app_user_student_classroom");
		Long id = jdbcTemplate.queryForObject(
				"INSERT INTO app_user (name, name_key, secret_hash, role) VALUES ('옛학생', '옛학생', 'h', 'STUDENT') RETURNING id",
				Long.class);
		String token = jwtService.generateAccessToken(id, "옛학생", "STUDENT");

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("옛학생"))
				.andExpect(jsonPath("$.classroom").value(nullValue()));
	}

	@Test
	void patchingTheClassCodeIsGone() throws Exception {
		String token = signupAndGetAccessToken("내정보코드삭제");

		mockMvc.perform(patch("/api/users/me/class-code")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"classCode\":\"ABC234\"}"))
				.andExpect(status().isNotFound());
	}
}
