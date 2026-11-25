package com.ssen.voca.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

// 교사의 수업 관리 (#34): 수정, 참여 닫기/열기, 코드 재발급, 학생 목록·내보내기, 삭제.
// 학생마다 새 학교 이름과 사용자 id를 쓰므로 메모리 시도 제한 키가 겹치지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TeacherClassroomManagementTest {

	private static final String CLOSED_MESSAGE = "지금은 이 수업에 참여할 수 없어요. 선생님께 문의해 주세요.";
	private static final String NOT_FOUND_ROOM = "수업을 찾을 수 없어요.";
	private static final String PRIVATE_WORD = "{\"id\":\"x\",\"word\":\"mine\",\"chapter\":\"My\",\"meanings\":[\"뜻\"],\"synonyms\":[],"
			+ "\"details\":{\"example\":\"e\",\"exampleKo\":\"\",\"exampleMeaning\":\"\"}}";

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

	private record Student(long id, String token) {
	}

	private JsonNode body(ResultActions result) throws Exception {
		return json.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String token, Object payload) throws Exception {
		MockHttpServletRequestBuilder built = auth(request, token);
		if (payload != null) {
			built.contentType(MediaType.APPLICATION_JSON)
					.content(payload instanceof String text ? text : json.writeValueAsString(payload));
		}
		// 테스트 전체가 한 트랜잭션이라 커밋이 없다. 단어 조회는 JDBC로 읽으므로 JPA 변경을 먼저 내보낸다.
		ResultActions result = mvc.perform(built);
		entityManager.flush();
		return result;
	}

	private JsonNode createRoom(String teacher, String name) throws Exception {
		return body(send(post("/api/teacher/classrooms"), teacher, Map.of("name", name)).andExpect(status().isCreated()));
	}

	private Student student(String name) throws Exception {
		return student("관리학교-" + UUID.randomUUID(), name);
	}

	private Student student(String school, String name) throws Exception {
		String token = body(mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("school", school, "name", name, "phoneLast4", "1234")))))
				.get("accessToken").asText();
		long id = body(mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))).get("id").asLong();
		return new Student(id, token);
	}

	private ResultActions join(Student student, String code) throws Exception {
		return send(post("/api/me/classroom"), student.token(), Map.of("code", code));
	}

	private void publish(String teacher, long roomId, String... words) throws Exception {
		StringBuilder items = new StringBuilder();
		for (String word : words) {
			items.append(items.isEmpty() ? "" : ",").append("{\"id\":\"x\",\"word\":\"").append(word)
					.append("\",\"chapter\":\"Week 1\",\"meanings\":[\"뜻\"],\"synonyms\":[],")
					.append("\"details\":{\"example\":\"e\",\"exampleKo\":\"\",\"exampleMeaning\":\"\"}}");
		}
		send(post("/api/teacher/classrooms/" + roomId + "/words"), teacher, "[" + items + "]").andExpect(status().isCreated());
	}

	private JsonNode words(Student student) throws Exception {
		return body(send(get("/api/me/words"), student.token(), null));
	}

	private JsonNode me(Student student) throws Exception {
		return body(send(get("/api/users/me"), student.token(), null));
	}

	private JsonNode list(String teacher) throws Exception {
		return body(send(get("/api/teacher/classrooms"), teacher, null));
	}

	private long countWhere(String sql, Object... args) {
		return jdbc.queryForObject("SELECT count(*) FROM " + sql, Long.class, args);
	}

	// ---- 수정 ----

	@Test
	void patchRenamesTheClassroomAndReturnsTheNewFields() throws Exception {
		String teacher = teachers.create().token();
		JsonNode room = createRoom(teacher, "옛 이름");

		send(patch("/api/teacher/classrooms/" + room.get("id").asLong()), teacher, Map.of("name", "  새 이름  "))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(room.get("id").asLong()))
				.andExpect(jsonPath("$.name").value("새 이름"))
				.andExpect(jsonPath("$.code").value(room.get("code").asText()))
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andExpect(jsonPath("$.joinable").value(true))
				.andExpect(jsonPath("$.studentCount").value(0));
		assertThat(list(teacher).get(0).get("name").asText()).isEqualTo("새 이름");
	}

	@Test
	void patchRejectsBlankTooLongAndEmptyBodies() throws Exception {
		String teacher = teachers.create().token();
		String path = "/api/teacher/classrooms/" + createRoom(teacher, "검증반").get("id").asLong();

		send(patch(path), teacher, Map.of("name", "　")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("수업 이름을 입력해 주세요"));
		send(patch(path), teacher, Map.of("name", "가".repeat(101))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("수업 이름은 100자 이하로 입력해 주세요"));
		send(patch(path), teacher, Map.of("name", "가".repeat(100))).andExpect(status().isOk());
		send(patch(path), teacher, "{}").andExpect(status().isBadRequest());
		assertThat(list(teacher).get(0).get("name").asText()).isEqualTo("가".repeat(100));
	}

	@Test
	void patchIsNotFoundForSomeoneElsesClassroomAndForbiddenForStudents() throws Exception {
		String owner = teachers.create().token();
		String other = teachers.create().token();
		long id = createRoom(owner, "남의 수업").get("id").asLong();

		send(patch("/api/teacher/classrooms/" + id), other, Map.of("name", "가로채기"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value(NOT_FOUND_ROOM));
		send(patch("/api/teacher/classrooms/999999999"), owner, Map.of("name", "없음")).andExpect(status().isNotFound());
		send(patch("/api/teacher/classrooms/" + id), student("교사아님").token(), Map.of("name", "학생")).andExpect(status().isForbidden());
		mvc.perform(patch("/api/teacher/classrooms/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"익명\"}"))
				.andExpect(status().isUnauthorized());
		assertThat(list(owner).get(0).get("name").asText()).isEqualTo("남의 수업");
	}

	// ---- 참여 닫기/열기 ----

	@Test
	void closedClassroomRejectsNewStudentsUntilReopened() throws Exception {
		String teacher = teachers.create().token();
		JsonNode room = createRoom(teacher, "닫힘반");
		String path = "/api/teacher/classrooms/" + room.get("id").asLong();
		String code = room.get("code").asText();
		Student newcomer = student("새학생");

		send(patch(path), teacher, Map.of("joinable", false)).andExpect(status().isOk())
				.andExpect(jsonPath("$.joinable").value(false));
		assertThat(list(teacher).get(0).get("joinable").asBoolean()).isFalse();
		join(newcomer, code).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(CLOSED_MESSAGE));
		assertThat(me(newcomer).get("classroom").isNull()).isTrue();

		send(patch(path), teacher, Map.of("joinable", true)).andExpect(status().isOk())
				.andExpect(jsonPath("$.joinable").value(true));
		join(newcomer, code).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(code));
	}

	@Test
	void attemptsOnAClosedClassroomAreNotRefunded() throws Exception {
		String teacher = teachers.create().token();
		JsonNode room = createRoom(teacher, "추측방어반");
		send(patch("/api/teacher/classrooms/" + room.get("id").asLong()), teacher, Map.of("joinable", false)).andExpect(status().isOk());
		Student guesser = student("추측학생");

		for (int i = 0; i < 5; i++) {
			join(guesser, room.get("code").asText()).andExpect(status().isForbidden());
		}
		join(guesser, room.get("code").asText()).andExpect(status().isTooManyRequests());
	}

	@Test
	void aStudentAlreadyInTheClosedClassroomMayRejoinWithTheSameCode() throws Exception {
		String teacher = teachers.create().token();
		JsonNode room = createRoom(teacher, "재참여반");
		String code = room.get("code").asText();
		Student member = student("기존학생");
		Student outsider = student("외부학생");
		join(member, code).andExpect(status().isOk());

		send(patch("/api/teacher/classrooms/" + room.get("id").asLong()), teacher, Map.of("joinable", false)).andExpect(status().isOk());

		join(member, code).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(room.get("id").asLong()));
		join(outsider, code).andExpect(status().isForbidden());
		assertThat(me(member).get("classroom").get("code").asText()).isEqualTo(code);
	}

	// ---- 코드 재발급 ----

	@Test
	void reissuedCodeWorksAndTheOldOneNoLongerDoes() throws Exception {
		String teacher = teachers.create().token();
		JsonNode room = createRoom(teacher, "재발급반");
		long id = room.get("id").asLong();
		String oldCode = room.get("code").asText();
		Student member = student("남는학생");
		Student late = student("늦은학생");
		join(member, oldCode).andExpect(status().isOk());

		JsonNode reissued = body(send(post("/api/teacher/classrooms/" + id + "/code"), teacher, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.name").value("재발급반"))
				.andExpect(jsonPath("$.studentCount").value(1)));
		String newCode = reissued.get("code").asText();

		assertThat(newCode).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}").isNotEqualTo(oldCode);
		join(late, oldCode).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("참여 코드를 확인해 주세요."));
		join(late, newCode).andExpect(status().isOk());
		assertThat(me(member).get("classroom").get("id").asLong()).isEqualTo(id);
		assertThat(me(member).get("classroom").get("code").asText()).isEqualTo(newCode);
		assertThat(list(teacher).get(0).get("code").asText()).isEqualTo(newCode);
	}

	@Test
	void reissueIsNotFoundForSomeoneElsesClassroomAndForbiddenForStudents() throws Exception {
		String owner = teachers.create().token();
		String other = teachers.create().token();
		JsonNode room = createRoom(owner, "코드보호반");
		String path = "/api/teacher/classrooms/" + room.get("id").asLong() + "/code";

		send(post(path), other, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value(NOT_FOUND_ROOM));
		send(post(path), student("코드학생").token(), null).andExpect(status().isForbidden());
		assertThat(list(owner).get(0).get("code").asText()).isEqualTo(room.get("code").asText());
	}

	// ---- 학생 수·목록 ----

	@Test
	void studentCountAndListCoverOnlyThisClassroomSortedBySchoolThenName() throws Exception {
		String teacher = teachers.create().token();
		JsonNode a = createRoom(teacher, "A반");
		JsonNode b = createRoom(teacher, "B반");
		String firstSchool = "가학교-" + UUID.randomUUID();
		String secondSchool = "나학교-" + UUID.randomUUID();
		join(student(secondSchool, "가온"), a.get("code").asText()).andExpect(status().isOk());
		join(student(firstSchool, "다은"), a.get("code").asText()).andExpect(status().isOk());
		join(student(firstSchool, "나래"), a.get("code").asText()).andExpect(status().isOk());
		join(student(firstSchool, "다른반"), b.get("code").asText()).andExpect(status().isOk());

		JsonNode rooms = list(teacher);
		assertThat(rooms.get(0).get("name").asText()).isEqualTo("B반");
		assertThat(rooms.get(0).get("studentCount").asInt()).isEqualTo(1);
		assertThat(rooms.get(1).get("studentCount").asInt()).isEqualTo(3);

		JsonNode students = body(send(get("/api/teacher/classrooms/" + a.get("id").asLong() + "/students"), teacher, null)
				.andExpect(status().isOk()));
		assertThat(students.findValuesAsText("name")).containsExactly("나래", "다은", "가온");
		assertThat(students.findValuesAsText("school")).containsExactly(firstSchool, firstSchool, secondSchool);
	}

	@Test
	void studentListNeverExposesInternalValues() throws Exception {
		String teacher = teachers.create().token();
		JsonNode room = createRoom(teacher, "비공개반");
		join(student("비공개학생"), room.get("code").asText()).andExpect(status().isOk());

		JsonNode students = body(send(get("/api/teacher/classrooms/" + room.get("id").asLong() + "/students"), teacher, null));

		assertThat(students).hasSize(1);
		assertThat(students.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("id", "name", "school");
		String raw = students.toString();
		assertThat(raw).doesNotContain("secret", "Hash", "nameKey", "name_key", "schoolKey", "school_key", "classroomId", "role");
	}

	@Test
	void studentListIsNotFoundForSomeoneElsesClassroomAndForbiddenForStudents() throws Exception {
		String owner = teachers.create().token();
		String other = teachers.create().token();
		String path = "/api/teacher/classrooms/" + createRoom(owner, "목록보호반").get("id").asLong() + "/students";

		send(get(path), other, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value(NOT_FOUND_ROOM));
		send(get(path), student("목록학생").token(), null).andExpect(status().isForbidden());
	}

	// ---- 내보내기 ----

	@Test
	void removingAStudentOnlyClearsTheirClassroom() throws Exception {
		String teacher = teachers.create().token();
		JsonNode room = createRoom(teacher, "내보내기반");
		long id = room.get("id").asLong();
		publish(teacher, id, "apple");
		Student leaving = student("나가는학생");
		Student staying = student("남는학생");
		join(leaving, room.get("code").asText()).andExpect(status().isOk());
		join(staying, room.get("code").asText()).andExpect(status().isOk());
		String classWordId = words(leaving).get(0).get("id").asText();
		send(post("/api/me/bookmarks"), leaving.token(), Map.of("wordId", classWordId)).andExpect(status().isNoContent());
		String privateId = body(send(put("/api/me/words/mine"), leaving.token(), PRIVATE_WORD)).get("id").asText();

		send(delete("/api/teacher/classrooms/" + id + "/students/" + leaving.id()), teacher, null).andExpect(status().isNoContent());

		assertThat(me(leaving).get("classroom").isNull()).isTrue();
		JsonNode remaining = words(leaving);
		assertThat(remaining).hasSize(1);
		assertThat(remaining.get(0).get("id").asText()).isEqualTo(privateId);
		assertThat(countWhere("app_user WHERE id = ?", leaving.id())).isEqualTo(1);
		assertThat(countWhere("bookmark WHERE user_id = ? AND word_id = ?", leaving.id(), classWordId)).isEqualTo(1);
		assertThat(me(staying).get("classroom").get("id").asLong()).isEqualTo(id);
		assertThat(list(teacher).get(0).get("studentCount").asInt()).isEqualTo(1);
	}

	@Test
	void aRemovedStudentCanJoinAgainWhileTheClassroomIsOpen() throws Exception {
		String teacher = teachers.create().token();
		JsonNode room = createRoom(teacher, "다시반");
		Student student = student("돌아온학생");
		join(student, room.get("code").asText()).andExpect(status().isOk());
		send(delete("/api/teacher/classrooms/" + room.get("id").asLong() + "/students/" + student.id()), teacher, null)
				.andExpect(status().isNoContent());

		join(student, room.get("code").asText()).andExpect(status().isOk());
		assertThat(me(student).get("classroom").get("id").asLong()).isEqualTo(room.get("id").asLong());
	}

	@Test
	void removingIsNotFoundForNonMembersAndForSomeoneElsesClassroom() throws Exception {
		String owner = teachers.create().token();
		String other = teachers.create().token();
		JsonNode room = createRoom(owner, "내보내기보호반");
		JsonNode otherRoom = createRoom(owner, "다른반");
		Student member = student("소속학생");
		Student inOther = student("다른반학생");
		Student nobody = student("무소속학생");
		join(member, room.get("code").asText()).andExpect(status().isOk());
		join(inOther, otherRoom.get("code").asText()).andExpect(status().isOk());
		String base = "/api/teacher/classrooms/" + room.get("id").asLong() + "/students/";

		send(delete(base + inOther.id()), owner, null).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("학생을 찾을 수 없어요."));
		send(delete(base + nobody.id()), owner, null).andExpect(status().isNotFound());
		send(delete(base + "999999999"), owner, null).andExpect(status().isNotFound());
		send(delete(base + member.id()), other, null).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(NOT_FOUND_ROOM));
		send(delete(base + member.id()), member.token(), null).andExpect(status().isForbidden());
		assertThat(me(member).get("classroom").get("id").asLong()).isEqualTo(room.get("id").asLong());
		assertThat(me(inOther).get("classroom").get("id").asLong()).isEqualTo(otherRoom.get("id").asLong());
	}

	// ---- 삭제 ----

	@Test
	void deletingAClassroomReleasesStudentsAndRemovesItsWordsAndBookmarks() throws Exception {
		String teacher = teachers.create().token();
		JsonNode doomed = createRoom(teacher, "사라질반");
		JsonNode kept = createRoom(teacher, "남는반");
		long doomedId = doomed.get("id").asLong();
		publish(teacher, doomedId, "apple", "banana");
		publish(teacher, kept.get("id").asLong(), "zebra");
		Student member = student("삭제학생");
		Student bystander = student("옆반학생");
		join(member, doomed.get("code").asText()).andExpect(status().isOk());
		join(bystander, kept.get("code").asText()).andExpect(status().isOk());
		String doomedWordId = words(member).get(0).get("id").asText();
		send(post("/api/me/bookmarks"), member.token(), Map.of("wordId", doomedWordId)).andExpect(status().isNoContent());
		String keptWordId = words(bystander).get(0).get("id").asText();
		send(post("/api/me/bookmarks"), bystander.token(), Map.of("wordId", keptWordId)).andExpect(status().isNoContent());
		String privateId = body(send(put("/api/me/words/mine"), member.token(), PRIVATE_WORD)).get("id").asText();
		assertThat(countWhere("lesson_word lw JOIN lesson l ON l.id = lw.lesson_id WHERE l.classroom_id = ?", doomedId)).isEqualTo(2);

		send(delete("/api/teacher/classrooms/" + doomedId), teacher, null).andExpect(status().isNoContent());

		assertThat(me(member).get("classroom").isNull()).isTrue();
		JsonNode memberWords = words(member);
		assertThat(memberWords).hasSize(1);
		assertThat(memberWords.get(0).get("id").asText()).isEqualTo(privateId);
		assertThat(countWhere("classroom WHERE id = ?", doomedId)).isZero();
		assertThat(countWhere("lesson WHERE classroom_id = ?", doomedId)).isZero();
		assertThat(countWhere("word WHERE id = ?", doomedWordId)).isZero();
		assertThat(countWhere("bookmark WHERE word_id = ?", doomedWordId)).isZero();
		assertThat(countWhere("word WHERE owner_id IS NULL AND NOT EXISTS (SELECT 1 FROM lesson_word lw WHERE lw.word_id = word.id)")).isZero();
		// 학생 계정과 개인 단어, 다른 수업의 단어와 북마크는 그대로다.
		assertThat(countWhere("app_user WHERE id = ?", member.id())).isEqualTo(1);
		assertThat(countWhere("word WHERE id = ?", privateId)).isEqualTo(1);
		assertThat(countWhere("word WHERE id = ?", keptWordId)).isEqualTo(1);
		assertThat(countWhere("bookmark WHERE word_id = ?", keptWordId)).isEqualTo(1);
		assertThat(words(bystander)).hasSize(1);
		assertThat(me(bystander).get("classroom").get("id").asLong()).isEqualTo(kept.get("id").asLong());
		assertThat(list(teacher)).hasSize(1);
		join(member, doomed.get("code").asText()).andExpect(status().isNotFound());
	}

	@Test
	void deletingAnEmptyClassroomWorks() throws Exception {
		String teacher = teachers.create().token();
		long id = createRoom(teacher, "빈반").get("id").asLong();

		send(delete("/api/teacher/classrooms/" + id), teacher, null).andExpect(status().isNoContent());

		assertThat(list(teacher)).isEmpty();
	}

	@Test
	void deleteIsNotFoundForSomeoneElsesClassroomAndForbiddenForStudents() throws Exception {
		String owner = teachers.create().token();
		String other = teachers.create().token();
		JsonNode room = createRoom(owner, "삭제보호반");
		long id = room.get("id").asLong();
		publish(owner, id, "keep");
		Student member = student("보호학생");
		join(member, room.get("code").asText()).andExpect(status().isOk());

		send(delete("/api/teacher/classrooms/" + id), other, null).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(NOT_FOUND_ROOM));
		send(delete("/api/teacher/classrooms/" + id), member.token(), null).andExpect(status().isForbidden());
		mvc.perform(delete("/api/teacher/classrooms/" + id)).andExpect(status().isUnauthorized());

		assertThat(list(owner)).hasSize(1);
		assertThat(words(member)).hasSize(1);
		assertThat(me(member).get("classroom").get("id").asLong()).isEqualTo(id);
	}
}
