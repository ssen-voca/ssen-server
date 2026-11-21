package com.ssen.voca.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

// 학생이 참여 코드로 수업에 들어가는 흐름 (#24). 학생마다 새 학교 이름과 사용자 id를 쓰므로 메모리 시도 제한 키가 겹치지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentJoinClassroomTest {

	private static final String NOT_FOUND_MESSAGE = "참여 코드를 확인해 주세요.";

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

	private String student(String name) throws Exception {
		String response = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("school", "참여학교-" + UUID.randomUUID(), "name", name, "phoneLast4", "1234"))))
				.andReturn().getResponse().getContentAsString();
		return json.readTree(response).get("accessToken").asText();
	}

	private Room classroom(String teacherToken, String name) throws Exception {
		JsonNode created = body(mvc.perform(post("/api/teacher/classrooms").header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", name)))));
		return new Room(created.get("id").asLong(), created.get("code").asText());
	}

	private void publish(String teacherToken, Room room, String... words) throws Exception {
		StringBuilder items = new StringBuilder();
		for (String word : words) {
			items.append(items.isEmpty() ? "" : ",").append("{\"id\":\"x\",\"word\":\"").append(word)
					.append("\",\"chapter\":\"Week 1\",\"number\":3,\"meanings\":[\"뜻\"],\"partsOfSpeech\":[\"명사\"],\"synonyms\":[],")
					.append("\"details\":{\"example\":\"e\",\"exampleKo\":\"\",\"exampleMeaning\":\"\",\"exampleForm\":\"").append(word).append("s\"}}");
		}
		mvc.perform(post("/api/teacher/classrooms/" + room.id() + "/words").header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
				.contentType(MediaType.APPLICATION_JSON).content("[" + items + "]")).andExpect(status().isCreated());
	}

	private ResultActions join(String token, String code) throws Exception {
		ResultActions result = mvc.perform(post("/api/me/classroom").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("code", code))));
		// 테스트 전체가 한 트랜잭션이라 커밋이 없다. 단어 조회는 JDBC로 읽으므로 JPA 변경을 먼저 내보낸다.
		entityManager.flush();
		return result;
	}

	private JsonNode words(String token) throws Exception {
		return body(mvc.perform(get("/api/me/words").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
	}

	private JsonNode me(String token) throws Exception {
		return body(mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
	}

	@Test
	void joiningWithTheRealCodeShowsTheClassroomAndItsWordsOnlyToMembers() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher, "A반");
		Room other = classroom(teacher, "B반");
		publish(teacher, room, "apple", "banana");
		publish(teacher, other, "zebra");
		String student = student("참여학생");
		String outsider = student("바깥학생");
		String inOther = student("다른반학생");
		join(inOther, other.code()).andExpect(status().isOk());

		join(student, room.code())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(room.id()))
				.andExpect(jsonPath("$.name").value("A반"))
				.andExpect(jsonPath("$.code").value(room.code()));

		assertThat(me(student).get("classroom").get("code").asText()).isEqualTo(room.code());
		JsonNode mine = words(student);
		assertThat(mine).hasSize(2);
		assertThat(mine.get(0).get("word").asText()).isEqualTo("apple");
		assertThat(mine.get(0).get("number").asInt()).isEqualTo(3);
		assertThat(mine.get(0).get("partsOfSpeech").get(0).asText()).isEqualTo("명사");
		assertThat(mine.get(0).get("details").get("exampleForm").asText()).isEqualTo("apples");
		assertThat(words(outsider)).isEmpty();
		JsonNode theirs = words(inOther);
		assertThat(theirs).hasSize(1);
		assertThat(theirs.get(0).get("word").asText()).isEqualTo("zebra");
	}

	@Test
	void lowerCaseAndSurroundingSpacesAreAccepted() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher, "공백반");
		String student = student("공백학생");

		join(student, "  " + room.code().toLowerCase() + " ")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(room.code()));
	}

	@Test
	void unknownAndMalformedCodesGetTheSame404AndLeaveTheClassroomUnchanged() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher, "그대로반");
		String student = student("그대로학생");
		join(student, room.code()).andExpect(status().isOk());
		String unknown = room.code().equals("AAAAAA") ? "BBBBBB" : "AAAAAA";

		for (String bad : new String[] {unknown, "I0O1L1", "ABCDEFG"}) {
			join(student, bad)
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.message").value(NOT_FOUND_MESSAGE));
		}
		assertThat(me(student).get("classroom").get("id").asLong()).isEqualTo(room.id());
	}

	@Test
	void switchingClassroomsShowsOnlyTheNewOnesWordsAndRejoiningIsOk() throws Exception {
		String teacher = teachers.create().token();
		Room a = classroom(teacher, "갈아타기A");
		Room b = classroom(teacher, "갈아타기B");
		publish(teacher, a, "alpha");
		publish(teacher, b, "beta");
		String student = student("갈아타기학생");

		join(student, a.code()).andExpect(status().isOk());
		assertThat(words(student).get(0).get("word").asText()).isEqualTo("alpha");
		join(student, b.code()).andExpect(status().isOk());
		JsonNode afterSwitch = words(student);
		assertThat(afterSwitch).hasSize(1);
		assertThat(afterSwitch.get(0).get("word").asText()).isEqualTo("beta");
		join(student, b.code()).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(b.id()));
		assertThat(words(student)).hasSize(1);
	}

	@Test
	void blankMissingOrTooLongCodeIs400() throws Exception {
		String student = student("빈코드학생");

		join(student, "   ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("참여 코드를 입력해 주세요"));
		join(student, "A".repeat(21)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(NOT_FOUND_MESSAGE));
		mvc.perform(post("/api/me/classroom").header(HttpHeaders.AUTHORIZATION, "Bearer " + student)
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("참여 코드를 입력해 주세요"));
	}

	@Test
	void teacherIs403AndNoTokenIs401() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher, "교사접근반");

		join(teacher, room.code()).andExpect(status().isForbidden());
		mvc.perform(post("/api/me/classroom").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("code", room.code()))))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void fiveWrongAttemptsBlockEvenTheCorrectCode() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher, "추측반");
		String student = student("추측학생");

		for (int i = 0; i < 5; i++) {
			join(student, "I0O1L1").andExpect(status().isNotFound());
		}
		join(student, room.code()).andExpect(status().isTooManyRequests());
		assertThat(me(student).get("classroom").isNull()).isTrue();
	}

	@Test
	void aSuccessfulJoinRefundsItsOwnCharge() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher, "환불반");
		String student = student("환불학생");

		for (int i = 0; i < 4; i++) {
			join(student, "I0O1L1").andExpect(status().isNotFound());
		}
		join(student, room.code()).andExpect(status().isOk());
		// 4회 실패 + 성공(환불) = 남은 몫 4회, 그래서 5번째 시도는 통과하고 6번째부터 막힌다.
		join(student, "I0O1L1").andExpect(status().isNotFound());
		join(student, room.code()).andExpect(status().isTooManyRequests());
	}

	@Test
	void classroomWordsCanBeBookmarkedAfterJoining() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher, "북마크반");
		publish(teacher, room, "mark");
		String student = student("북마크학생");
		join(student, room.code()).andExpect(status().isOk());
		String wordId = words(student).get(0).get("id").asText();

		mvc.perform(post("/api/me/bookmarks").header(HttpHeaders.AUTHORIZATION, "Bearer " + student)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("wordId", wordId))))
				.andExpect(status().isNoContent());
		assertThat(body(mvc.perform(get("/api/me/bookmarks").header(HttpHeaders.AUTHORIZATION, "Bearer " + student))).get(0).asText())
				.isEqualTo(wordId);
	}
}
