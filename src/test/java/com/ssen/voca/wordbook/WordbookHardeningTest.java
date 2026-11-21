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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 요청 크기 상한을 작게 낮춰 큰 본문 거절을 검증하고, 교사 단어 등록의 재등록(중복 방지)과 수업 단위 접근을 검증한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.max-request-bytes=4000")
class WordbookHardeningTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private JdbcTemplate db;

	@Value("${app.teacher.invite-code}")
	private String inviteCode;

	private static String word(String text, String meaning, String chapter, int padding) {
		return "{\"id\":\"x\",\"word\":\"" + text + "\",\"chapter\":\"" + chapter + "\",\"meanings\":[\"" + meaning
				+ "\"],\"synonyms\":[\"\"],\"details\":{\"example\":\"" + "e".repeat(padding)
				+ "\",\"exampleKo\":\"\",\"exampleMeaning\":\"\"}}";
	}

	private static String words(String... items) {
		return "[" + String.join(",", items) + "]";
	}

	private JsonNode postJson(String path, Map<String, String> body) throws Exception {
		return json.readTree(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(body))).andReturn().getResponse().getContentAsString());
	}

	private String studentToken() throws Exception {
		return postJson("/api/auth/signup", Map.of("school", UUID.randomUUID().toString(), "name", "학생", "phoneLast4", "1234"))
				.get("accessToken").asText();
	}

	private long userId(String token) throws Exception {
		return json.readTree(mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andReturn().getResponse().getContentAsString()).get("id").asLong();
	}

	private String teacherToken() throws Exception {
		return postJson("/api/teacher/signup", Map.of("name", "교사", "email", UUID.randomUUID() + "@example.com",
				"password", "password1", "inviteCode", inviteCode)).get("accessToken").asText();
	}

	private long classroom(String teacherToken) throws Exception {
		String body = mvc.perform(post("/api/teacher/classrooms").header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"수업\"}"))
				.andReturn().getResponse().getContentAsString();
		return json.readTree(body).get("id").asLong();
	}

	private JsonNode catalog(String token) throws Exception {
		return json.readTree(mvc.perform(get("/api/me/words").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andReturn().getResponse().getContentAsString());
	}

	@Test
	void oversizedBodyIsRejectedBeforeAnyWordIsStored() throws Exception {
		String token = studentToken();
		String tooBig = words(word("big", "큰", "Week 1", 5000));
		mvc.perform(post("/api/me/words/import").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(tooBig))
				.andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.message").value("요청이 너무 커요."));
		assertThat(catalog(token)).isEmpty();
	}

	@Test
	void bodyWithinTheLimitIsAccepted() throws Exception {
		String token = studentToken();
		mvc.perform(post("/api/me/words/import").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(words(word("small", "작은", "Week 1", 10))))
				.andExpect(status().isNoContent());
		assertThat(catalog(token)).hasSize(1);
	}

	@Test
	void savingANewPrivateWordReturnsTheStoredId() throws Exception {
		String token = studentToken();
		String body = word("mine", "내 것", "Week 1", 0);
		String first = mvc.perform(put("/api/me/words/{id}", "client-id").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String id = json.readTree(first).get("id").asText();
		assertThat(catalog(token)).hasSize(1);
		assertThat(catalog(token).get(0).get("id").asText()).isEqualTo(id);

		mvc.perform(put("/api/me/words/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(word("mine", "고친 뜻", "Week 1", 0)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
		assertThat(catalog(token)).hasSize(1);
		assertThat(catalog(token).get(0).get("meanings").get(0).asText()).isEqualTo("고친 뜻");
	}

	@Test
	void publishingTheSameWordsAgainUpdatesThemAndKeepsBookmarks() throws Exception {
		String teacher = teacherToken();
		long classroomId = classroom(teacher);
		String student = studentToken();
		db.update("UPDATE app_user SET classroom_id = ? WHERE id = ?", classroomId, userId(student));

		String publish = "/api/teacher/classrooms/" + classroomId + "/words";
		mvc.perform(post(publish).header(HttpHeaders.AUTHORIZATION, "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content(words(word("insight", "통찰", "Week 1", 0), word("cultivate", "기르다", "Week 1", 0))))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.created").value(2))
				.andExpect(jsonPath("$.updated").value(0));
		assertThat(catalog(student)).hasSize(2);

		String insightId = null;
		for (JsonNode w : catalog(student)) {
			if (w.get("word").asText().equals("insight")) insightId = w.get("id").asText();
		}
		mvc.perform(post("/api/me/bookmarks").header(HttpHeaders.AUTHORIZATION, "Bearer " + student)
				.contentType(MediaType.APPLICATION_JSON).content("{\"wordId\":\"" + insightId + "\"}"))
				.andExpect(status().isNoContent());

		// 같은 단어(대소문자·앞뒤 공백 무시)를 다시 올리면 새로 만들지 않고 고친다.
		mvc.perform(post(publish).header(HttpHeaders.AUTHORIZATION, "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content(words(word("Insight ", "통찰력", "Week 1", 0), word("cultivate", "기르다", "Week 1", 0))))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.created").value(0))
				.andExpect(jsonPath("$.updated").value(2));

		JsonNode after = catalog(student);
		assertThat(after).hasSize(2);
		for (JsonNode w : after) {
			if (w.get("word").asText().trim().equalsIgnoreCase("insight")) {
				assertThat(w.get("id").asText()).isEqualTo(insightId);
				assertThat(w.get("meanings").get(0).asText()).isEqualTo("통찰력");
			}
		}
		mvc.perform(get("/api/me/bookmarks").header(HttpHeaders.AUTHORIZATION, "Bearer " + student))
				.andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0]").value(insightId));
	}

	@Test
	void sameWordInAnotherChapterIsAnotherEntry() throws Exception {
		String teacher = teacherToken();
		long classroomId = classroom(teacher);
		mvc.perform(post("/api/teacher/classrooms/" + classroomId + "/words")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + teacher).contentType(MediaType.APPLICATION_JSON)
				.content(words(word("insight", "통찰", "Week 1", 0), word("insight", "통찰", "Week 2", 0))))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.created").value(2));
	}

	@Test
	void otherTeachersCannotPublishToThisClassroom() throws Exception {
		long classroomId = classroom(teacherToken());
		mvc.perform(post("/api/teacher/classrooms/" + classroomId + "/words")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken()).contentType(MediaType.APPLICATION_JSON)
				.content(words(word("insight", "통찰", "Week 1", 0))))
				.andExpect(status().isNotFound());
	}

	@Test
	void studentsOutsideTheClassroomNeitherSeeNorBookmarkItsWords() throws Exception {
		String teacher = teacherToken();
		long classroomId = classroom(teacher);
		mvc.perform(post("/api/teacher/classrooms/" + classroomId + "/words")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + teacher).contentType(MediaType.APPLICATION_JSON)
				.content(words(word("insight", "통찰", "Week 1", 0)))).andExpect(status().isCreated());
		String id = db.queryForObject("SELECT id FROM word WHERE payload->>'word' = 'insight' AND owner_id IS NULL ORDER BY created_at DESC LIMIT 1", String.class);

		String outsider = studentToken();
		assertThat(catalog(outsider)).isEmpty();
		mvc.perform(post("/api/me/bookmarks").header(HttpHeaders.AUTHORIZATION, "Bearer " + outsider)
				.contentType(MediaType.APPLICATION_JSON).content("{\"wordId\":\"" + id + "\"}"))
				.andExpect(status().isNotFound());
		mvc.perform(put("/api/me/words/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + outsider)
				.contentType(MediaType.APPLICATION_JSON).content(word("insight", "바꿈", "Week 1", 0)))
				.andExpect(status().isForbidden());
		mvc.perform(delete("/api/me/words/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + outsider))
				.andExpect(status().isForbidden());
	}

	private static String wordWith(String extraTopLevel, String extraDetails) {
		return "{\"id\":\"x\",\"word\":\"study\",\"chapter\":\"Week 1\",\"meanings\":[\"공부하다\",\"서재\"]," + extraTopLevel
				+ "\"synonyms\":[\"learn\",\"\"],\"details\":{\"example\":\"He studied.\",\"exampleKo\":\"그는 공부했다.\",\"exampleMeaning\":\"공부했다\""
				+ extraDetails + "}}";
	}

	@Test
	void teacherExcelFieldsAreStoredAndReturnedToStudents() throws Exception {
		String teacher = teacherToken();
		long classroomId = classroom(teacher);
		String student = studentToken();
		db.update("UPDATE app_user SET classroom_id = ? WHERE id = ?", classroomId, userId(student));
		String body = words(wordWith("\"number\":3,\"partsOfSpeech\":[\"동사\",\"명사\"],", ",\"exampleForm\":\"studied\""));
		mvc.perform(post("/api/teacher/classrooms/" + classroomId + "/words").header(HttpHeaders.AUTHORIZATION, "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
		JsonNode stored = catalog(student).get(0);
		assertThat(stored.get("number").asInt()).isEqualTo(3);
		assertThat(stored.get("partsOfSpeech").get(1).asText()).isEqualTo("명사");
		assertThat(stored.get("details").get("exampleForm").asText()).isEqualTo("studied");
	}

	@Test
	void teacherExcelFieldsAreOptional() throws Exception {
		String teacher = teacherToken();
		long classroomId = classroom(teacher);
		mvc.perform(post("/api/teacher/classrooms/" + classroomId + "/words").header(HttpHeaders.AUTHORIZATION, "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON).content(words(wordWith("", "")))).andExpect(status().isCreated());
	}

	@Test
	void malformedExcelFieldsAreRejected() throws Exception {
		String teacher = teacherToken();
		long classroomId = classroom(teacher);
		String path = "/api/teacher/classrooms/" + classroomId + "/words";
		String[][] bad = {
				{"\"number\":\"3\",", "", "번호를 확인해 주세요."},
				{"\"number\":-1,", "", "번호를 확인해 주세요."},
				{"\"number\":1.5,", "", "번호를 확인해 주세요."},
				{"\"partsOfSpeech\":[\"동사\"],", "", "품사를 확인해 주세요."},
				{"\"partsOfSpeech\":[\"동사\",5],", "", "품사를 확인해 주세요."},
				{"\"partsOfSpeech\":[\"" + "가".repeat(31) + "\",\"\"],", "", "품사를 확인해 주세요."},
				{"", ",\"exampleForm\":5", "예문 활용형을 확인해 주세요."},
				{"", ",\"exampleForm\":\"" + "a".repeat(101) + "\"", "예문 활용형을 확인해 주세요."},
		};
		for (String[] c : bad) {
			mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + teacher)
					.contentType(MediaType.APPLICATION_JSON).content(words(wordWith(c[0], c[1]))))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(c[2]));
		}
	}
}
