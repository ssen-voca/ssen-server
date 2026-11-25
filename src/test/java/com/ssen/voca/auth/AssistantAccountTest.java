package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// 조교(#29): 교사와 같은 권한이고 표시(position)만 다르다. 학교마다 새 이름을 써서 시도 제한 키가 겹치지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
class AssistantAccountTest {

	private static final String PIN = "2468";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private TeacherAccountSeeder seeder;

	@Autowired
	private JdbcTemplate jdbc;

	private ResultActions login(String school, String name) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("school", school, "name", name, "phoneLast4", PIN))));
	}

	private String token(String school, String name) throws Exception {
		return json.readTree(login(school, name).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("accessToken").asText();
	}

	private ResultActions me(String token) throws Exception {
		return mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	@Test
	void usersMeShowsTheRolePositionForTeachersAssistantsAndNullForStudents() throws Exception {
		String school = "조교학원-" + UUID.randomUUID();
		seeder.seed(List.of(
				new TeacherAccounts.Entry(school, "김선생", PIN),
				new TeacherAccounts.Entry(school, "박조교", PIN, "ASSISTANT")));
		String student = json.readTree(mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("school", school, "name", "학생", "phoneLast4", "1357"))))
				.andReturn().getResponse().getContentAsString()).get("accessToken").asText();

		me(token(school, "김선생")).andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("TEACHER")).andExpect(jsonPath("$.position").value("TEACHER"));
		me(token(school, "박조교")).andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("TEACHER")).andExpect(jsonPath("$.position").value("ASSISTANT"));
		me(student).andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("STUDENT")).andExpect(jsonPath("$.position").doesNotExist());
	}

	@Test
	void anAssistantHasExactlyTheTeacherRights() throws Exception {
		String school = "조교학원-" + UUID.randomUUID();
		seeder.seed(List.of(new TeacherAccounts.Entry(school, "박조교", PIN, "ASSISTANT")));
		String token = token(school, "박조교");

		mvc.perform(post("/api/teacher/classrooms").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"조교반\"}")).andExpect(status().isCreated());
		mvc.perform(get("/api/teacher/classrooms").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void reSeedingChangesThePositionWithoutTouchingTheAccountOrItsData() throws Exception {
		String school = "조교학원-" + UUID.randomUUID();
		seeder.seed(List.of(new TeacherAccounts.Entry(school, "김선생", PIN)));
		String before = token(school, "김선생");
		mvc.perform(post("/api/teacher/classrooms").header(HttpHeaders.AUTHORIZATION, "Bearer " + before)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"반\"}")).andExpect(status().isCreated());
		Long id = json.readTree(me(before).andReturn().getResponse().getContentAsString()).get("id").asLong();

		seeder.seed(List.of(new TeacherAccounts.Entry(school, "김선생", PIN, "ASSISTANT")));

		me(before).andExpect(jsonPath("$.position").value("ASSISTANT"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Long.class, id)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM classroom WHERE teacher_id = ?", Long.class, id)).isEqualTo(1);

		seeder.seed(List.of(new TeacherAccounts.Entry(school, "김선생", PIN)));

		me(before).andExpect(jsonPath("$.position").value("TEACHER"));
		assertThat(jdbc.queryForObject("SELECT teacher_position FROM app_user WHERE id = ?", String.class, id)).isNull();
	}

	@Test
	void anAssistantRemovedFromTheConfigIsDisabledLikeAnyTeacher() throws Exception {
		String school = "조교학원-" + UUID.randomUUID();
		String other = "조교학원-" + UUID.randomUUID();
		seeder.seed(List.of(new TeacherAccounts.Entry(school, "박조교", PIN, "ASSISTANT")));
		String token = token(school, "박조교");

		seeder.seed(List.of(new TeacherAccounts.Entry(other, "다른선생", PIN)));

		me(token).andExpect(status().isUnauthorized());
		login(school, "박조교").andExpect(status().isUnauthorized());
	}

	@Test
	void aTeacherRowWithoutAPositionCountsAsATeacher() throws Exception {
		String school = "조교학원-" + UUID.randomUUID();
		seeder.seed(List.of(new TeacherAccounts.Entry(school, "박조교", PIN, "ASSISTANT")));
		String token = token(school, "박조교");
		Long id = json.readTree(me(token).andReturn().getResponse().getContentAsString()).get("id").asLong();

		// V12 이전에 만들어진 교사 행처럼 값이 없는 상태.
		jdbc.update("UPDATE app_user SET teacher_position = NULL WHERE id = ?", id);

		me(token).andExpect(jsonPath("$.position").value("TEACHER"));
	}

	@Test
	void theDatabaseRejectsAPositionOnAStudentOrAnUnknownValue() throws Exception {
		String school = "조교학원-" + UUID.randomUUID();
		String student = json.readTree(mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("school", school, "name", "학생", "phoneLast4", "1357"))))
				.andReturn().getResponse().getContentAsString()).get("accessToken").asText();
		Long studentId = json.readTree(me(student).andReturn().getResponse().getContentAsString()).get("id").asLong();

		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> jdbc.update("UPDATE app_user SET teacher_position = 'ASSISTANT' WHERE id = ?", studentId))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}
}
