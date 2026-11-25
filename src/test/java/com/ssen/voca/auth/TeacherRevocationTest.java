package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssen.voca.classroom.ClassroomRepository;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
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

// 설정(TEACHER_ACCOUNTS)에서 뺀 교사는 로그인·갱신·이미 받은 토큰이 모두 막히고, 다시 넣으면 모두 돌아온다. 데이터는 지워지지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TeacherRevocationTest {


	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private TeacherAccountSeeder seeder;

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private ClassroomRepository classroomRepository;

	private String school;
	private TeacherAccounts.Entry kim;
	private TeacherAccounts.Entry park;

	@BeforeEach
	void registerTeachers() {
		school = "회수학원-" + UUID.randomUUID();
		kim = new TeacherAccounts.Entry(school, "김선생", "2468");
		park = new TeacherAccounts.Entry(school, "박선생", "1357");
		seeder.seed(List.of(kim, park));
	}

	private ResultActions login(String name, String pin) throws Exception {
		return mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("school", school, "name", name, "phoneLast4", pin))));
	}

	private JsonNode body(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private ResultActions getWith(String path, String token) throws Exception {
		return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))));
	}

	@Test
	void aTeacherRemovedFromTheConfigIsLockedOutEverywhere() throws Exception {
		JsonNode tokens = body(login("김선생", kim.pin()).andExpect(status().isOk()));
		String access = tokens.get("accessToken").asText();
		String refresh = tokens.get("refreshToken").asText();
		getWith("/api/teacher/classrooms", access).andExpect(status().isOk());

		seeder.seed(List.of(park));

		// 로그인은 없는 계정과 같은 401과 같은 메시지다.
		login("김선생", kim.pin()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("학교, 이름 또는 휴대폰 번호가 올바르지 않아요."));
		login("김선생", "0000").andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("학교, 이름 또는 휴대폰 번호가 올바르지 않아요."));
		refresh(refresh).andExpect(status().isUnauthorized());
		// 이미 발급된 액세스 토큰은 교사 경로와 공용 경로 모두 막힌다.
		getWith("/api/teacher/classrooms", access).andExpect(status().isUnauthorized());
		getWith("/api/users/me", access).andExpect(status().isUnauthorized());
	}

	@Test
	void puttingTheTeacherBackRestoresLoginRefreshAndTheOldToken() throws Exception {
		JsonNode tokens = body(login("김선생", kim.pin()).andExpect(status().isOk()));
		String access = tokens.get("accessToken").asText();
		String refresh = tokens.get("refreshToken").asText();
		seeder.seed(List.of(park));
		getWith("/api/users/me", access).andExpect(status().isUnauthorized());

		seeder.seed(List.of(kim, park));

		login("김선생", kim.pin()).andExpect(status().isOk());
		refresh(refresh).andExpect(status().isOk());
		getWith("/api/users/me", access).andExpect(status().isOk());
		getWith("/api/teacher/classrooms", access).andExpect(status().isOk());
	}

	@Test
	void anEmptyConfigDisablesNobody() throws Exception {
		String access = body(login("김선생", kim.pin()).andExpect(status().isOk())).get("accessToken").asText();

		seeder.seed(List.of());

		login("김선생", kim.pin()).andExpect(status().isOk());
		getWith("/api/teacher/classrooms", access).andExpect(status().isOk());
	}

	@Test
	void otherTeachersAndStudentsAreUnaffected() throws Exception {
		String parkToken = body(login("박선생", park.pin()).andExpect(status().isOk())).get("accessToken").asText();
		String student = body(mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("school", school, "name", "이학생", "phoneLast4", "9753")))
				.with(r -> {
					r.setRemoteAddr("10.0.0.9");
					return r;
				}))).get("accessToken").asText();

		seeder.seed(List.of(park));

		getWith("/api/teacher/classrooms", parkToken).andExpect(status().isOk());
		login("박선생", park.pin()).andExpect(status().isOk());
		getWith("/api/users/me", student).andExpect(status().isOk());
		login("이학생", "9753").andExpect(status().isOk());
	}

	@Test
	void aStudentWithTheRemovedTeachersSchoolAndNameStillLogsIn() throws Exception {
		mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("school", school, "name", "김선생", "phoneLast4", "9753"))))
				.andExpect(status().isCreated());
		seeder.seed(List.of(park));

		String token = body(login("김선생", "9753").andExpect(status().isOk())).get("accessToken").asText();

		getWith("/api/users/me", token).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("STUDENT"));
		// 비활성 교사의 번호로는 학생 계정 로그인이 되지 않고, 가입도 여전히 충돌이다 (번호 재사용으로 교사 자리를 가로채지 못한다).
		login("김선생", kim.pin()).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								Map.of("school", school, "name", "김선생", "phoneLast4", kim.pin()))))
				.andExpect(status().isConflict());
	}

	@Test
	void theTeachersClassroomsAndAccountSurviveRemoval() throws Exception {
		String access = body(login("김선생", kim.pin()).andExpect(status().isOk())).get("accessToken").asText();
		mockMvc.perform(post("/api/teacher/classrooms")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"남는 수업\"}"))
				.andExpect(status().isCreated());
		Long teacherId = appUserRepository
				.findAllBySchoolKeyAndNameKeyAndRole(Schools.key(school), Names.key("김선생"), AppUser.TEACHER)
				.get(0).getId();

		seeder.seed(List.of(park));

		assertThat(appUserRepository.findById(teacherId)).isPresent();
		assertThat(classroomRepository.findAllByTeacherIdOrderByIdDesc(teacherId)).hasSize(1);
		seeder.seed(List.of(kim, park));
		getWith("/api/teacher/classrooms", access).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void aTeacherTokenForADeletedAccountIsUnauthorized() throws Exception {
		String access = body(login("김선생", kim.pin()).andExpect(status().isOk())).get("accessToken").asText();
		Long id = appUserRepository
				.findAllBySchoolKeyAndNameKeyAndRole(Schools.key(school), Names.key("김선생"), AppUser.TEACHER)
				.get(0).getId();

		appUserRepository.deleteById(id);
		appUserRepository.flush();

		getWith("/api/users/me", access).andExpect(status().isUnauthorized());
	}
}
