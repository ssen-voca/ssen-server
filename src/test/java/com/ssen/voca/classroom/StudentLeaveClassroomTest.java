package com.ssen.voca.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

// 학생이 수업에서 나가는 흐름 (#31). 학생마다 새 학교 이름과 사용자 id를 쓰므로 메모리 시도 제한 키가 겹치지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentLeaveClassroomTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private TeacherFixture teachers;

	@Autowired
	private EntityManager entityManager;

	private record Room(long id, String code) {
	}

	private JsonNode body(ResultActions result) throws Exception {
		return json.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private String auth(String token) {
		return "Bearer " + token;
	}

	private String student(String name) throws Exception {
		String response = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("school", "나가기학교-" + UUID.randomUUID(), "name", name, "phoneLast4", "1234"))))
				.andReturn().getResponse().getContentAsString();
		return json.readTree(response).get("accessToken").asText();
	}

	private Room classroom(String teacherToken, String name) throws Exception {
		JsonNode created = body(mvc.perform(post("/api/teacher/classrooms").header(HttpHeaders.AUTHORIZATION, auth(teacherToken))
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", name)))));
		return new Room(created.get("id").asLong(), created.get("code").asText());
	}

	private void publish(String teacherToken, Room room, String word) throws Exception {
		String item = "{\"id\":\"x\",\"word\":\"" + word + "\",\"chapter\":\"Week 1\",\"meanings\":[\"뜻\"],\"synonyms\":[],"
				+ "\"details\":{\"example\":\"e\",\"exampleKo\":\"\",\"exampleMeaning\":\"\"}}";
		mvc.perform(post("/api/teacher/classrooms/" + room.id() + "/words").header(HttpHeaders.AUTHORIZATION, auth(teacherToken))
				.contentType(MediaType.APPLICATION_JSON).content("[" + item + "]")).andExpect(status().isCreated());
	}

	private void join(String token, String code) throws Exception {
		mvc.perform(post("/api/me/classroom").header(HttpHeaders.AUTHORIZATION, auth(token))
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("code", code))))
				.andExpect(status().isOk());
		// 테스트 전체가 한 트랜잭션이라 커밋이 없다. 단어 조회는 JDBC로 읽으므로 JPA 변경을 먼저 내보낸다.
		entityManager.flush();
	}

	private ResultActions leave(String token) throws Exception {
		ResultActions result = mvc.perform(delete("/api/me/classroom").header(HttpHeaders.AUTHORIZATION, auth(token)));
		entityManager.flush();
		return result;
	}

	private JsonNode words(String token) throws Exception {
		return body(mvc.perform(get("/api/me/words").header(HttpHeaders.AUTHORIZATION, auth(token))));
	}

	private JsonNode me(String token) throws Exception {
		return body(mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, auth(token))));
	}

	private void bookmark(String token, String wordId) throws Exception {
		mvc.perform(post("/api/me/bookmarks").header(HttpHeaders.AUTHORIZATION, auth(token))
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("wordId", wordId))))
				.andExpect(status().isNoContent());
	}

	@Test
	void leavingRemovesTheClassroomAndItsTeacherWordsButKeepsPrivateWordsAndTheAccount() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher, "A반");
		publish(teacher, room, "apple");
		String student = student("나가는학생");
		join(student, room.code());
		String classWordId = words(student).get(0).get("id").asText();
		bookmark(student, classWordId);
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/me/words/mine")
				.header(HttpHeaders.AUTHORIZATION, auth(student)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"id\":\"mine\",\"word\":\"mine\",\"chapter\":\"My\",\"meanings\":[\"내 단어\"],\"synonyms\":[],"
						+ "\"details\":{\"example\":\"e\",\"exampleKo\":\"\",\"exampleMeaning\":\"\"}}"))
				.andExpect(status().isOk());
		assertThat(words(student)).hasSize(2);

		leave(student).andExpect(status().isNoContent());

		assertThat(me(student).get("classroom").isNull()).isTrue();
		JsonNode after = words(student);
		assertThat(after).hasSize(1);
		assertThat(after.get(0).get("word").asText()).isEqualTo("mine");
		// 수업 단어의 북마크는 보이지 않는다(단어가 지워진 게 아니라 볼 수 없게 된 것).
		mvc.perform(get("/api/me/bookmarks").header(HttpHeaders.AUTHORIZATION, auth(student)))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
	}

	@Test
	void joiningTheSameClassroomAgainBringsTheWordsBackAndTheBookmarkWithThem() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher, "A반");
		publish(teacher, room, "apple");
		String student = student("돌아오는학생");
		join(student, room.code());
		String classWordId = words(student).get(0).get("id").asText();
		bookmark(student, classWordId);
		leave(student).andExpect(status().isNoContent());

		join(student, room.code());

		assertThat(words(student)).hasSize(1);
		mvc.perform(get("/api/me/bookmarks").header(HttpHeaders.AUTHORIZATION, auth(student)))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[\"" + classWordId + "\"]"));
	}

	@Test
	void leavingWithoutAClassroomIsHarmlessAndRepeatable() throws Exception {
		String student = student("수업없는학생");

		leave(student).andExpect(status().isNoContent());
		leave(student).andExpect(status().isNoContent());

		assertThat(me(student).get("classroom").isNull()).isTrue();
	}

	@Test
	void leavingOnlyAffectsTheCallerNotClassmates() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher, "A반");
		publish(teacher, room, "apple");
		String leaver = student("나가는친구");
		String stayer = student("남는친구");
		join(leaver, room.code());
		join(stayer, room.code());

		leave(leaver).andExpect(status().isNoContent());

		assertThat(me(stayer).get("classroom").get("code").asText()).isEqualTo(room.code());
		assertThat(words(stayer)).hasSize(1);
	}

	@Test
	void teacherAndAnonymousCannotLeave() throws Exception {
		String teacher = teachers.create().token();

		leave(teacher).andExpect(status().isForbidden());
		mvc.perform(delete("/api/me/classroom")).andExpect(status().isUnauthorized());
	}
}
