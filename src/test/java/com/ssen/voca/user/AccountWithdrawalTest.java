package com.ssen.voca.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssen.voca.support.TeacherFixture;
import jakarta.persistence.EntityManager;
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
import org.springframework.transaction.annotation.Transactional;

// 학생 회원 탈퇴 (#37). 학생마다 새 학교 이름과 사용자 id를 쓰므로 메모리 시도 제한 키가 겹치지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountWithdrawalTest {

	private static final String PIN = "1234";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private TeacherFixture teachers;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbc;

	private record Student(long id, String school, String name, String token, String refreshToken) {
	}

	private JsonNode body(ResultActions result) throws Exception {
		return json.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private String auth(String token) {
		return "Bearer " + token;
	}

	private ResultActions signup(String school, String name) throws Exception {
		return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("school", school, "name", name, "phoneLast4", PIN))));
	}

	private Student student(String name) throws Exception {
		String school = "탈퇴학교-" + UUID.randomUUID();
		JsonNode tokens = body(signup(school, name));
		String access = tokens.get("accessToken").asText();
		long id = body(mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, auth(access)))).get("id").asLong();
		return new Student(id, school, name, access, tokens.get("refreshToken").asText());
	}

	private ResultActions withdraw(String token, String pin) throws Exception {
		ResultActions result = mvc.perform(post("/api/me/withdraw").header(HttpHeaders.AUTHORIZATION, auth(token))
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("phoneLast4", pin))));
		// 테스트 전체가 한 트랜잭션이라 커밋이 없다. JDBC로 읽기 전에 JPA 변경을 먼저 내보낸다.
		entityManager.flush();
		return result;
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private void putPrivateWord(String token, String id) throws Exception {
		mvc.perform(put("/api/me/words/" + id).header(HttpHeaders.AUTHORIZATION, auth(token)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"id\":\"" + id + "\",\"word\":\"mine\",\"chapter\":\"My\",\"meanings\":[\"내 단어\"],\"synonyms\":[],"
						+ "\"details\":{\"example\":\"e\",\"exampleKo\":\"\",\"exampleMeaning\":\"\"}}"))
				.andExpect(status().isOk());
	}

	@Test
	void withdrawingDeletesTheAccountAndMakesEveryTokenUseless() throws Exception {
		Student student = student("탈퇴학생");

		withdraw(student.token(), PIN).andExpect(status().isNoContent());

		assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", student.id())).isZero();
		mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, auth(student.token()))).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me/words").header(HttpHeaders.AUTHORIZATION, auth(student.token()))).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("refreshToken", student.refreshToken())))).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("school", student.school(), "name", student.name(), "phoneLast4", PIN))))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void withdrawingDeletesBookmarksAndPrivateWordsButKeepsTheTeachersClassWords() throws Exception {
		var teacher = teachers.create();
		long classroomId = body(mvc.perform(post("/api/teacher/classrooms").header(HttpHeaders.AUTHORIZATION, auth(teacher.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", "A반"))))).get("id").asLong();
		String code = body(mvc.perform(get("/api/teacher/classrooms").header(HttpHeaders.AUTHORIZATION, auth(teacher.token())))).get(0).get("code").asText();
		mvc.perform(post("/api/teacher/classrooms/" + classroomId + "/words").header(HttpHeaders.AUTHORIZATION, auth(teacher.token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("[{\"id\":\"x\",\"word\":\"apple\",\"chapter\":\"Week 1\",\"meanings\":[\"뜻\"],\"synonyms\":[],"
						+ "\"details\":{\"example\":\"e\",\"exampleKo\":\"\",\"exampleMeaning\":\"\"}}]"))
				.andExpect(status().isCreated());
		Student leaving = student("떠나는학생");
		Student staying = student("남는학생");
		for (Student s : new Student[] {leaving, staying}) {
			mvc.perform(post("/api/me/classroom").header(HttpHeaders.AUTHORIZATION, auth(s.token())).contentType(MediaType.APPLICATION_JSON)
					.content(json.writeValueAsString(Map.of("code", code)))).andExpect(status().isOk());
		}
		entityManager.flush();
		String classWordId = body(mvc.perform(get("/api/me/words").header(HttpHeaders.AUTHORIZATION, auth(leaving.token())))).get(0).get("id").asText();
		mvc.perform(post("/api/me/bookmarks").header(HttpHeaders.AUTHORIZATION, auth(leaving.token())).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("wordId", classWordId)))).andExpect(status().isNoContent());
		putPrivateWord(leaving.token(), "mine-" + UUID.randomUUID());
		assertThat(count("SELECT count(*) FROM word WHERE owner_id = ?", leaving.id())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM bookmark WHERE user_id = ?", leaving.id())).isEqualTo(1);

		withdraw(leaving.token(), PIN).andExpect(status().isNoContent());

		assertThat(count("SELECT count(*) FROM word WHERE owner_id = ?", leaving.id())).isZero();
		assertThat(count("SELECT count(*) FROM bookmark WHERE user_id = ?", leaving.id())).isZero();
		assertThat(count("SELECT count(*) FROM word WHERE id = ?", classWordId)).isEqualTo(1);
		mvc.perform(get("/api/me/words").header(HttpHeaders.AUTHORIZATION, auth(staying.token())))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].word").value("apple"));
		assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", staying.id())).isEqualTo(1);
	}

	@Test
	void wrongNumberIsForbiddenNotUnauthorizedAndKeepsTheAccount() throws Exception {
		Student student = student("번호틀린학생");

		withdraw(student.token(), "0000").andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("번호가 올바르지 않아요."));

		assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", student.id())).isEqualTo(1);
		mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, auth(student.token()))).andExpect(status().isOk());
	}

	@Test
	void repeatedWrongNumbersAreThrottledEvenForTheRightNumber() throws Exception {
		Student student = student("제한걸린학생");
		for (int i = 0; i < 5; i++) {
			withdraw(student.token(), "0000").andExpect(status().isForbidden());
		}

		withdraw(student.token(), PIN).andExpect(status().isTooManyRequests());

		assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", student.id())).isEqualTo(1);
	}

	@Test
	void sameSchoolAndNameCanSignUpAgainAfterWithdrawing() throws Exception {
		Student student = student("다시가입학생");
		withdraw(student.token(), PIN).andExpect(status().isNoContent());

		signup(student.school(), student.name()).andExpect(status().isCreated());
	}

	@Test
	void teachersAnonymousAndBadRequestsAreRejected() throws Exception {
		var teacher = teachers.create();
		Student student = student("형식틀린학생");

		withdraw(teacher.token(), teacher.pin()).andExpect(status().isForbidden());
		mvc.perform(post("/api/me/withdraw").contentType(MediaType.APPLICATION_JSON).content("{\"phoneLast4\":\"1234\"}"))
				.andExpect(status().isUnauthorized());
		withdraw(student.token(), "12a4").andExpect(status().isBadRequest());
		withdraw(student.token(), "123").andExpect(status().isBadRequest());
		mvc.perform(post("/api/me/withdraw").header(HttpHeaders.AUTHORIZATION, auth(student.token()))
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
		assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", student.id())).isEqualTo(1);
	}
}
