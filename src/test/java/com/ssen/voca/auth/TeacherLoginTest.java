package com.ssen.voca.auth;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

// 서버 설정으로 등록한 교사가 학생과 같은 로그인으로 교사 권한을 얻는지 검증한다. 테스트마다 새 학교를 쓰므로 제한 키가 겹치지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TeacherLoginTest {

	private static final String PIN = "2468";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private TeacherAccountSeeder seeder;

	private String school;

	@BeforeEach
	void registerTeacher() {
		school = "교사학원-" + UUID.randomUUID();
		seeder.seed(List.of(new TeacherAccounts.Entry(school, "김선생", PIN)));
	}

	private ResultActions auth(String path, String school, String name, String pin) throws Exception {
		return mockMvc.perform(post(path)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("school", school, "name", name, "phoneLast4", pin))));
	}

	private String accessToken(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("accessToken").asText();
	}

	private ResultActions listClassrooms(String token) throws Exception {
		return mockMvc.perform(get("/api/teacher/classrooms").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	@Test
	void registeredTeacherLogsInWithTheStudentEndpointAndGetsTeacherRights() throws Exception {
		String token = accessToken(auth("/api/auth/login", school, "김선생", PIN).andExpect(status().isOk()));

		listClassrooms(token).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		mockMvc.perform(post("/api/teacher/classrooms")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"교사 수업\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("교사 수업"));
		listClassrooms(token).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void loginAcceptsDifferentSpellingOfSchoolAndName() throws Exception {
		auth("/api/auth/login", " " + school.toUpperCase() + " ", "김선생", PIN).andExpect(status().isOk());
	}

	@Test
	void meShowsTheTeacherRoleAndSchool() throws Exception {
		String token = accessToken(auth("/api/auth/login", school, "김선생", PIN));

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("TEACHER"))
				.andExpect(jsonPath("$.name").value("김선생"))
				.andExpect(jsonPath("$.school").value(school))
				.andExpect(jsonPath("$.email").value(nullValue()))
				.andExpect(jsonPath("$.classroom").value(nullValue()));
	}

	@Test
	void wrongPinIsUnauthorized() throws Exception {
		auth("/api/auth/login", school, "김선생", "0000")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("학교, 이름 또는 휴대폰 번호가 올바르지 않아요."));
	}

	@Test
	void unknownTeacherLikeLoginIsUnauthorized() throws Exception {
		auth("/api/auth/login", school, "박선생", PIN).andExpect(status().isUnauthorized());
		auth("/api/auth/login", school + "-다른학교", "김선생", PIN).andExpect(status().isUnauthorized());
	}

	@Test
	void studentTokenIsForbiddenOnTheTeacherApi() throws Exception {
		String token = accessToken(auth("/api/auth/signup", school, "이학생", "1357").andExpect(status().isCreated()));

		listClassrooms(token).andExpect(status().isForbidden());
	}

	@Test
	void studentSigningUpWithTheTeachersSchoolAndNameButAnotherPinStaysAStudent() throws Exception {
		String token = accessToken(auth("/api/auth/signup", school, "김선생", "1357").andExpect(status().isCreated()));

		listClassrooms(token).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(jsonPath("$.role").value("STUDENT"));
		// 같은 (학교, 이름)이어도 각자 자기 번호로 로그인하면 각자의 계정이다.
		String student = accessToken(auth("/api/auth/login", school, "김선생", "1357").andExpect(status().isOk()));
		listClassrooms(student).andExpect(status().isForbidden());
		String teacher = accessToken(auth("/api/auth/login", school, "김선생", PIN).andExpect(status().isOk()));
		listClassrooms(teacher).andExpect(status().isOk());
	}

	@Test
	void signingUpWithTheTeachersPinIsAConflictAndGivesNoToken() throws Exception {
		auth("/api/auth/signup", school, "김선생", PIN)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.accessToken").doesNotExist());
	}

	@Test
	void registeringATeacherWhoseSchoolNamePinMatchAnExistingStudentIsRefused() throws Exception {
		// 그대로 두면 그 학생이 교사 권한으로 로그인하게 되므로 등록 단계에서 막는다.
		String other = "겹침학원-" + UUID.randomUUID();
		auth("/api/auth/signup", other, "박선생", PIN).andExpect(status().isCreated());

		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> seeder.seed(List.of(new TeacherAccounts.Entry(other, "박선생", PIN))))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void noTokenIsStillUnauthorizedOnTheTeacherApi() throws Exception {
		mockMvc.perform(get("/api/teacher/classrooms")).andExpect(status().isUnauthorized());
	}

	@Test
	void theOldEmailTeacherEndpointsAreGone() throws Exception {
		mockMvc.perform(post("/api/teacher/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"a@b.com\",\"password\":\"password1\"}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/teacher/signup").contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"x\",\"email\":\"a@b.com\",\"password\":\"password1\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void fiveWrongPinsLockTheTeacherOutAndSquatSignupsShareTheBucket() throws Exception {
		for (int i = 0; i < 4; i++) {
			auth("/api/auth/login", school, "김선생", "000" + i).andExpect(status().isUnauthorized());
		}
		// 가입 시도도 같은 (학교, 이름) 버킷의 5번째 시도로 센다.
		auth("/api/auth/signup", school, "김선생", "1357").andExpect(status().isCreated());

		auth("/api/auth/login", school, "김선생", PIN).andExpect(status().isTooManyRequests());
	}
}
