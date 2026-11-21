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
import com.ssen.voca.auth.JwtService;
import com.ssen.voca.classroom.Classroom;
import com.ssen.voca.classroom.ClassroomRepository;
import com.ssen.voca.support.TeacherFixture;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

	private String school;

	@BeforeEach
	void newSchool() {
		school = "내정보 학교-" + UUID.randomUUID();
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private ClassroomRepository classroomRepository;

	private JsonNode signup(String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new SignupRequest(school, name, "1234"))))
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

	@Autowired
	private TeacherFixture teacherFixture;

	@Test
	void legacyEmailTeacherMeIncludesEmailAndRole() throws Exception {
		// 이메일로 가입했던 옛 교사 행(V9 이전). 학교 없이 이메일만 있어도 CHECK를 통과한다.
		Long id = jdbcTemplate.queryForObject(
				"INSERT INTO app_user (name, name_key, secret_hash, email, role) "
						+ "VALUES ('내정보교사', '내정보교사', 'h', 'me-teacher@example.com', 'TEACHER') RETURNING id",
				Long.class);
		String token = jwtService.generateAccessToken(id, "내정보교사", "TEACHER");

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("TEACHER"))
				.andExpect(jsonPath("$.email").value("me-teacher@example.com"));
	}

	@Test
	void teacherMeShowsRoleAndSchoolWithNullEmail() throws Exception {
		TeacherFixture.Teacher teacher = teacherFixture.create("내정보교사");

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + teacher.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("TEACHER"))
				.andExpect(jsonPath("$.school").value(teacher.school()))
				.andExpect(jsonPath("$.email").value(nullValue()));
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
	void studentMeShowsTheSchoolAndNoClassroom() throws Exception {
		String token = signupAndGetAccessToken("내정보학교");

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.school").value(school))
				.andExpect(jsonPath("$.classroom").value(nullValue()))
				.andExpect(jsonPath("$.classCode").doesNotExist());
	}

	@Test
	void teacherMeHasNoClassroom() throws Exception {
		String token = teacherFixture.create("내정보교사수업").token();

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(jsonPath("$.classroom").value(nullValue()));
	}

	@Test
	void legacyStudentWithoutSchoolStillGetsMe() throws Exception {
		// 학교가 없던 시절의 학생 행(V7 이전). CHECK 제약이 NOT VALID라 기존 행은 남을 수 있어서, 테스트 트랜잭션 안에서만 제약을 풀어 만들고 토큰은 직접 만든다.
		jdbcTemplate.execute("ALTER TABLE app_user DROP CONSTRAINT ck_app_user_student_school");
		Long id = jdbcTemplate.queryForObject(
				"INSERT INTO app_user (name, name_key, secret_hash, role) VALUES ('옛학생', '옛학생', 'h', 'STUDENT') RETURNING id",
				Long.class);
		String token = jwtService.generateAccessToken(id, "옛학생", "STUDENT");

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("옛학생"))
				.andExpect(jsonPath("$.school").value(nullValue()))
				.andExpect(jsonPath("$.classroom").value(nullValue()));
	}

	@Test
	void legacyStudentWithClassroomButNoSchoolStillShowsTheClassroom() throws Exception {
		jdbcTemplate.execute("ALTER TABLE app_user DROP CONSTRAINT ck_app_user_student_school");
		Long teacherId = teacherFixture.create("옛수업교사").id();
		Classroom classroom = classroomRepository.saveAndFlush(
				new Classroom(teacherId, "옛수업", "L" + UUID.randomUUID().toString().substring(0, 5).toUpperCase()));
		Long id = jdbcTemplate.queryForObject(
				"INSERT INTO app_user (name, name_key, secret_hash, classroom_id, role) "
						+ "VALUES ('옛수업학생', '옛수업학생', 'h', ?, 'STUDENT') RETURNING id",
				Long.class, classroom.getId());
		String token = jwtService.generateAccessToken(id, "옛수업학생", "STUDENT");

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.school").value(nullValue()))
				.andExpect(jsonPath("$.classroom.id").value(classroom.getId()))
				.andExpect(jsonPath("$.classroom.name").value("옛수업"))
				.andExpect(jsonPath("$.classroom.code").value(classroom.getCode()));
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
