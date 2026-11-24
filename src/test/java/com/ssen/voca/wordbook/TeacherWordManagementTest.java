package com.ssen.voca.wordbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssen.voca.support.TeacherFixture;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 교사가 올린 단어의 조회·수정·삭제, 차시 삭제, 업로드 replace 옵션 (#33).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TeacherWordManagementTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private TeacherFixture teachers;

	@Autowired
	private JdbcTemplate db;

	@Autowired
	private EntityManager entityManager;

	private record Room(long id, String code) {
	}

	private String auth(String token) {
		return "Bearer " + token;
	}

	private JsonNode body(ResultActions result) throws Exception {
		return json.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private Room classroom(String teacherToken) throws Exception {
		JsonNode created = body(mvc.perform(post("/api/teacher/classrooms").header(HttpHeaders.AUTHORIZATION, auth(teacherToken))
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", "A반")))));
		return new Room(created.get("id").asLong(), created.get("code").asText());
	}

	private String student(String name) throws Exception {
		String response = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("school", "관리학교-" + UUID.randomUUID(), "name", name, "phoneLast4", "1234"))))
				.andReturn().getResponse().getContentAsString();
		return json.readTree(response).get("accessToken").asText();
	}

	private String studentIn(Room room, String name) throws Exception {
		String token = student(name);
		mvc.perform(post("/api/me/classroom").header(HttpHeaders.AUTHORIZATION, auth(token))
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("code", room.code())))).andExpect(status().isOk());
		entityManager.flush(); // 단어 조회는 JDBC라서 JPA 변경을 먼저 내보낸다.
		return token;
	}

	private String word(String word, String chapter, Integer number) {
		return "{\"id\":\"x\",\"word\":\"" + word + "\",\"chapter\":\"" + chapter + "\","
				+ (number == null ? "" : "\"number\":" + number + ",")
				+ "\"meanings\":[\"뜻\"],\"synonyms\":[],\"details\":{\"example\":\"e\",\"exampleKo\":\"\",\"exampleMeaning\":\"\"}}";
	}

	private ResultActions upload(String teacherToken, Room room, String query, String... words) throws Exception {
		return mvc.perform(post("/api/teacher/classrooms/" + room.id() + "/words" + query).header(HttpHeaders.AUTHORIZATION, auth(teacherToken))
				.contentType(MediaType.APPLICATION_JSON).content("[" + String.join(",", words) + "]"));
	}

	private void publish(String teacherToken, Room room, String... words) throws Exception {
		upload(teacherToken, room, "", words).andExpect(status().isCreated());
	}

	private JsonNode list(String teacherToken, Room room) throws Exception {
		return body(mvc.perform(get("/api/teacher/classrooms/" + room.id() + "/words").header(HttpHeaders.AUTHORIZATION, auth(teacherToken)))
				.andExpect(status().isOk()));
	}

	private String idOf(String teacherToken, Room room, String word) throws Exception {
		for (JsonNode w : list(teacherToken, room)) {
			if (w.get("word").asText().equals(word)) {
				return w.get("id").asText();
			}
		}
		throw new AssertionError("단어 없음: " + word);
	}

	private ResultActions update(String teacherToken, Room room, String wordId, String payload) throws Exception {
		return mvc.perform(put("/api/teacher/classrooms/" + room.id() + "/words/" + wordId).header(HttpHeaders.AUTHORIZATION, auth(teacherToken))
				.contentType(MediaType.APPLICATION_JSON).content(payload));
	}

	private ResultActions deleteWord(String teacherToken, Room room, String wordId) throws Exception {
		return mvc.perform(delete("/api/teacher/classrooms/" + room.id() + "/words/" + wordId).header(HttpHeaders.AUTHORIZATION, auth(teacherToken)));
	}

	private ResultActions deleteChapter(String teacherToken, Room room, String chapter) throws Exception {
		return mvc.perform(delete("/api/teacher/classrooms/{id}/chapters/{chapter}", room.id(), chapter).header(HttpHeaders.AUTHORIZATION, auth(teacherToken)));
	}

	private void bookmark(String studentToken, String wordId) throws Exception {
		mvc.perform(post("/api/me/bookmarks").header(HttpHeaders.AUTHORIZATION, auth(studentToken))
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("wordId", wordId)))).andExpect(status().isNoContent());
	}

	private JsonNode bookmarks(String studentToken) throws Exception {
		return body(mvc.perform(get("/api/me/bookmarks").header(HttpHeaders.AUTHORIZATION, auth(studentToken))));
	}

	private JsonNode studentWords(String studentToken) throws Exception {
		return body(mvc.perform(get("/api/me/words").header(HttpHeaders.AUTHORIZATION, auth(studentToken))));
	}

	private int count(String sql, Object... args) {
		return db.queryForObject(sql, Integer.class, args);
	}

	// ---- 조회 ----

	@Test
	void listIsOrderedByLessonThenNumberThenCreation() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		publish(teacher, room, word("w1c", "Week 1", null), word("w1b", "Week 1", 2), word("w1a", "Week 1", 1), word("w1d", "Week 1", null));
		publish(teacher, room, word("w2a", "Week 2", 1));
		// 한 트랜잭션 안의 created_at은 모두 같아서, 번호 없는 두 단어의 순서는 만든 시각으로만 갈리게 직접 벌려 둔다.
		db.update("UPDATE word SET created_at=now()+interval '1 second' WHERE id=?", idOf(teacher, room, "w1d"));

		JsonNode words = list(teacher, room);

		List<String> order = new ArrayList<>();
		words.forEach(w -> order.add(w.get("word").asText()));
		// Week 1(먼저 만든 lesson): 번호 1, 2, 그다음 번호 없는 것은 created_at 순. 그 뒤 Week 2.
		assertThat(order).containsExactly("w1a", "w1b", "w1c", "w1d", "w2a");
		assertThat(words.get(0).get("chapter").asText()).isEqualTo("Week 1");
		assertThat(words.get(0).get("id").asText()).isNotEqualTo("x");
	}

	@Test
	void listIsEmptyForAClassroomWithoutWords() throws Exception {
		String teacher = teachers.create().token();
		assertThat(list(teacher, classroom(teacher))).isEmpty();
	}

	@Test
	void listIsHiddenFromOtherTeachersStudentsAndAnonymous() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		publish(teacher, room, word("apple", "Week 1", 1));
		String other = teachers.create("다른교사").token();

		mvc.perform(get("/api/teacher/classrooms/" + room.id() + "/words").header(HttpHeaders.AUTHORIZATION, auth(other)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("수업을 찾을 수 없어요."));
		mvc.perform(get("/api/teacher/classrooms/" + room.id() + "/words").header(HttpHeaders.AUTHORIZATION, auth(student("학생"))))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/teacher/classrooms/" + room.id() + "/words")).andExpect(status().isUnauthorized());
	}

	// ---- 수정 ----

	@Test
	void updateKeepsIdLessonAndBookmarksAndForcesTheChapter() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		publish(teacher, room, word("apple", "Week 1", 1), word("pear", "Week 2", 1));
		String student = studentIn(room, "북마크학생");
		String id = idOf(teacher, room, "apple");
		bookmark(student, id);

		// 요청의 id와 chapter는 무시한다.
		update(teacher, room, id, word("apple pie", "Week 2", 5).replace("\"id\":\"x\"", "\"id\":\"other\"").replace("[\"뜻\"]", "[\"새 뜻\"]"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.chapter").value("Week 1"))
				.andExpect(jsonPath("$.word").value("apple pie"))
				.andExpect(jsonPath("$.number").value(5))
				.andExpect(jsonPath("$.meanings[0]").value("새 뜻"));

		JsonNode saved = list(teacher, room);
		assertThat(saved).hasSize(2);
		assertThat(saved.get(0).get("id").asText()).isEqualTo(id);
		assertThat(saved.get(0).get("chapter").asText()).isEqualTo("Week 1");
		assertThat(count("SELECT count(*) FROM lesson WHERE classroom_id=?", room.id())).isEqualTo(2);
		assertThat(bookmarks(student)).extracting(JsonNode::asText).containsExactly(id);
		assertThat(studentWords(student).toString()).contains("apple pie");
	}

	@Test
	void updateRejectsInvalidWordsWithoutChangingAnything() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		publish(teacher, room, word("apple", "Week 1", 1));
		String id = idOf(teacher, room, "apple");

		update(teacher, room, id, word("apple", "Week 1", 1).replace("[\"뜻\"]", "[]")).andExpect(status().isBadRequest());
		update(teacher, room, id, "{}").andExpect(status().isBadRequest());

		assertThat(list(teacher, room).get(0).get("meanings").get(0).asText()).isEqualTo("뜻");
	}

	@Test
	void updateToAWordAlreadyInTheSameLessonIsAConflictButSelfAndOtherLessonsAreFine() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		publish(teacher, room, word("apple", "Week 1", 1), word("pear", "Week 1", 2), word("fig", "Week 2", 1));
		String pear = idOf(teacher, room, "pear");

		update(teacher, room, pear, word("  APPLE ", "Week 1", 2)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("같은 단어가 이미 있어요."));
		update(teacher, room, pear, word("PEAR", "Week 1", 2)).andExpect(status().isOk()); // 자기 자신은 제외
		update(teacher, room, pear, word("fig", "Week 1", 2)).andExpect(status().isOk()); // 다른 lesson의 같은 단어는 괜찮다
	}

	@Test
	void updateOfAnotherTeachersClassroomOrAnotherClassroomsWordIsNotFound() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		Room second = classroom(teacher);
		publish(teacher, room, word("apple", "Week 1", 1));
		String id = idOf(teacher, room, "apple");
		String other = teachers.create("다른교사").token();

		update(other, room, id, word("apple", "Week 1", 1)).andExpect(status().isNotFound());
		update(teacher, second, id, word("apple", "Week 1", 1)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("단어를 찾을 수 없어요."));
		update(teacher, room, "no-such-word", word("apple", "Week 1", 1)).andExpect(status().isNotFound());
		mvc.perform(put("/api/teacher/classrooms/" + room.id() + "/words/" + id).contentType(MediaType.APPLICATION_JSON)
				.content(word("apple", "Week 1", 1))).andExpect(status().isUnauthorized());
		mvc.perform(put("/api/teacher/classrooms/" + room.id() + "/words/" + id).header(HttpHeaders.AUTHORIZATION, auth(student("학생")))
				.contentType(MediaType.APPLICATION_JSON).content(word("apple", "Week 1", 1))).andExpect(status().isForbidden());
		assertThat(list(teacher, room).get(0).get("word").asText()).isEqualTo("apple");
	}

	// ---- 단어 삭제 ----

	@Test
	void deleteWordRemovesItsBookmarksAndKeepsTheRest() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		publish(teacher, room, word("apple", "Week 1", 1), word("pear", "Week 1", 2));
		String student = studentIn(room, "삭제학생");
		String apple = idOf(teacher, room, "apple");
		String pear = idOf(teacher, room, "pear");
		bookmark(student, apple);
		bookmark(student, pear);

		deleteWord(teacher, room, apple).andExpect(status().isNoContent());

		assertThat(list(teacher, room)).hasSize(1);
		assertThat(count("SELECT count(*) FROM word WHERE id=?", apple)).isZero();
		assertThat(count("SELECT count(*) FROM bookmark WHERE word_id=?", apple)).isZero();
		assertThat(bookmarks(student)).extracting(JsonNode::asText).containsExactly(pear);
		assertThat(count("SELECT count(*) FROM lesson WHERE classroom_id=?", room.id())).isEqualTo(1);
		// 학생 쪽 목록에서도 사라진다.
		assertThat(studentWords(student)).hasSize(1);
		assertThat(studentWords(student).get(0).get("word").asText()).isEqualTo("pear");
	}

	@Test
	void deletingTheLastWordAlsoDeletesTheEmptyLesson() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		publish(teacher, room, word("apple", "Week 1", 1), word("fig", "Week 2", 1));

		deleteWord(teacher, room, idOf(teacher, room, "apple")).andExpect(status().isNoContent());

		assertThat(db.queryForList("SELECT chapter FROM lesson WHERE classroom_id=?", String.class, room.id())).containsExactly("Week 2");
	}

	@Test
	void deleteWordIsNotFoundForUnknownOtherClassroomAndOtherTeacher() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		Room second = classroom(teacher);
		publish(teacher, room, word("apple", "Week 1", 1));
		String id = idOf(teacher, room, "apple");
		String other = teachers.create("다른교사").token();

		deleteWord(teacher, room, "no-such-word").andExpect(status().isNotFound());
		deleteWord(teacher, second, id).andExpect(status().isNotFound());
		deleteWord(other, room, id).andExpect(status().isNotFound());
		mvc.perform(delete("/api/teacher/classrooms/" + room.id() + "/words/" + id)).andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/teacher/classrooms/" + room.id() + "/words/" + id).header(HttpHeaders.AUTHORIZATION, auth(student("학생"))))
				.andExpect(status().isForbidden());
		assertThat(list(teacher, room)).hasSize(1);
		deleteWord(teacher, room, id).andExpect(status().isNoContent());
		deleteWord(teacher, room, id).andExpect(status().isNotFound());
	}

	// ---- 차시 삭제 ----

	@Test
	void deleteChapterRemovesItsWordsBookmarksAndLessonOnly() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		publish(teacher, room, word("apple", "Week 1", 1), word("pear", "Week 1", 2), word("fig", "Week 2", 1));
		String student = studentIn(room, "차시학생");
		String apple = idOf(teacher, room, "apple");
		String pear = idOf(teacher, room, "pear");
		String fig = idOf(teacher, room, "fig");
		bookmark(student, apple);
		bookmark(student, pear);
		bookmark(student, fig);

		deleteChapter(teacher, room, "Week 1").andExpect(status().isNoContent());

		JsonNode left = list(teacher, room);
		assertThat(left).hasSize(1);
		assertThat(left.get(0).get("id").asText()).isEqualTo(fig);
		assertThat(count("SELECT count(*) FROM word WHERE id IN (?,?)", apple, pear)).isZero();
		assertThat(count("SELECT count(*) FROM bookmark WHERE word_id IN (?,?)", apple, pear)).isZero();
		assertThat(db.queryForList("SELECT chapter FROM lesson WHERE classroom_id=?", String.class, room.id())).containsExactly("Week 2");
		assertThat(bookmarks(student)).extracting(JsonNode::asText).containsExactly(fig);
		assertThat(studentWords(student)).hasSize(1);
		deleteChapter(teacher, room, "Week 1").andExpect(status().isNotFound());
	}

	@Test
	void deleteChapterIsNotFoundForUnknownChapterOtherClassroomOtherTeacherAndTooLongName() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		Room second = classroom(teacher);
		publish(teacher, room, word("apple", "Week 1", 1));
		String other = teachers.create("다른교사").token();

		deleteChapter(teacher, room, "Week 9").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("차시를 찾을 수 없어요."));
		deleteChapter(teacher, second, "Week 1").andExpect(status().isNotFound());
		deleteChapter(other, room, "Week 1").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("수업을 찾을 수 없어요."));
		deleteChapter(teacher, room, "x".repeat(101)).andExpect(status().isNotFound()); // 100자 초과는 404로 고정
		mvc.perform(delete("/api/teacher/classrooms/{id}/chapters/{chapter}", room.id(), "Week 1")).andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/teacher/classrooms/{id}/chapters/{chapter}", room.id(), "Week 1").header(HttpHeaders.AUTHORIZATION, auth(student("학생"))))
				.andExpect(status().isForbidden());
		assertThat(list(teacher, room)).hasSize(1);
	}

	// ---- replace ----

	@Test
	void replaceDeletesOnlyMissingWordsOfUploadedChaptersAndCountsThem() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		publish(teacher, room, word("apple", "Week 1", 1), word("pear", "Week 1", 2), word("fig", "Week 2", 1), word("kiwi", "Week 3", 1));
		String student = studentIn(room, "교체학생");
		String apple = idOf(teacher, room, "apple");
		String pear = idOf(teacher, room, "pear");
		bookmark(student, apple);
		bookmark(student, pear);

		// Week 1에는 apple(대소문자·공백만 다름)과 새 grape, Week 2에는 fig만 올린다. Week 3은 올리지 않는다.
		upload(teacher, room, "?replace=true", word(" APPLE ", "Week 1", 1), word("grape", "Week 1", 3), word("fig", "Week 2", 1))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.created").value(1))
				.andExpect(jsonPath("$.updated").value(2))
				.andExpect(jsonPath("$.deleted").value(1));

		List<String> now = new ArrayList<>();
		list(teacher, room).forEach(w -> now.add(w.get("word").asText().trim()));
		assertThat(now).containsExactlyInAnyOrder("APPLE", "grape", "fig", "kiwi");
		assertThat(idOf(teacher, room, " APPLE ")).isEqualTo(apple);
		assertThat(bookmarks(student)).extracting(JsonNode::asText).containsExactly(apple); // 지운 pear의 북마크는 사라진다
		assertThat(count("SELECT count(*) FROM word WHERE id=?", pear)).isZero();
	}

	@Test
	void withoutReplaceNothingIsDeletedAndTheResponseHasNoDeleted() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		publish(teacher, room, word("apple", "Week 1", 1), word("pear", "Week 1", 2));

		upload(teacher, room, "", word("apple", "Week 1", 1)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.created").value(0)).andExpect(jsonPath("$.updated").value(1)).andExpect(jsonPath("$.deleted").doesNotExist());
		upload(teacher, room, "?replace=false", word("apple", "Week 1", 1)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.deleted").doesNotExist());

		assertThat(list(teacher, room)).hasSize(2);
	}

	@Test
	void replaceWithAnEmptyListChangesNothing() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		publish(teacher, room, word("apple", "Week 1", 1));

		upload(teacher, room, "?replace=true").andExpect(status().isCreated()).andExpect(jsonPath("$.deleted").value(0));

		assertThat(list(teacher, room)).hasSize(1);
	}

	@Test
	void replaceMustBeTrueOrFalse() throws Exception {
		String teacher = teachers.create().token();
		Room room = classroom(teacher);
		publish(teacher, room, word("apple", "Week 1", 1));

		for (String bad : new String[] { "?replace=yes", "?replace=1", "?replace=" }) {
			upload(teacher, room, bad, word("grape", "Week 1", 1)).andExpect(status().isBadRequest());
		}
		assertThat(list(teacher, room)).hasSize(1);
	}

	// 롤백은 테스트 트랜잭션 안에서는 볼 수 없어서 이 테스트만 트랜잭션 없이 실제로 커밋하고 직접 정리한다.
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void invalidWordInTheUploadRollsBackEverythingIncludingReplace() throws Exception {
		TeacherFixture.Teacher teacher = teachers.create();
		Room room = classroom(teacher.token());
		try {
			publish(teacher.token(), room, word("apple", "Week 1", 1), word("pear", "Week 1", 2));
			String before = list(teacher.token(), room).toString();

			// apple 수정과 새 단어 추가는 처리된 뒤 세 번째 단어에서 검증이 실패한다.
			upload(teacher.token(), room, "?replace=true", word("apple", "Week 1", 9), word("grape", "Week 1", 3), "{}")
					.andExpect(status().isBadRequest());

			assertThat(list(teacher.token(), room).toString()).isEqualTo(before);
			assertThat(count("SELECT count(*) FROM lesson WHERE classroom_id=?", room.id())).isEqualTo(1);
		} finally {
			db.update("DELETE FROM word WHERE id IN (SELECT lw.word_id FROM lesson_word lw JOIN lesson l ON l.id=lw.lesson_id WHERE l.classroom_id=?)", room.id());
			db.update("DELETE FROM classroom WHERE id=?", room.id());
			db.update("DELETE FROM app_user WHERE id=?", teacher.id());
		}
	}
}
