package com.ssen.voca.classroom;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssen.voca.support.ClassroomFixture;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TeacherClassroomControllerTest {

	private static final String CODE_PATTERN = "[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ClassroomFixture fixture;

	@Value("${app.teacher.invite-code}")
	private String inviteCode;

	private JsonNode postForJson(String path, Map<String, String> body) throws Exception {
		String response = mockMvc.perform(MockMvcRequestBuilders.post(path)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(body)))
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response);
	}

	private JsonNode teacherTokens(String name, String email) throws Exception {
		return postForJson("/api/teacher/signup",
				Map.of("name", name, "email", email, "password", "password1", "inviteCode", inviteCode));
	}

	private String teacherToken(String name, String email) throws Exception {
		return teacherTokens(name, email).get("accessToken").asText();
	}

	private ResultActions createClassroom(String token, String name) throws Exception {
		return mockMvc.perform(MockMvcRequestBuilders.post("/api/teacher/classrooms")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name))));
	}

	private ResultActions listClassrooms(String token) throws Exception {
		return mockMvc.perform(MockMvcRequestBuilders.get("/api/teacher/classrooms")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	@Test
	void createReturns201WithCode() throws Exception {
		String token = teacherToken("수업교사", "class-create@example.com");

		createClassroom(token, "겨울 특강 A반")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").value("겨울 특강 A반"))
				.andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.matchesPattern(CODE_PATTERN)))
				.andExpect(jsonPath("$.createdAt").isNotEmpty());
	}

	@Test
	void createTrimsTheName() throws Exception {
		String token = teacherToken("수업교사둘", "class-trim@example.com");

		createClassroom(token, "  B반  ")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("B반"));
	}

	@Test
	void blankOrTooLongNameIsRejected() throws Exception {
		String token = teacherToken("수업교사셋", "class-name@example.com");

		createClassroom(token, "　")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("수업 이름을 입력해 주세요"));
		createClassroom(token, "가".repeat(101))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("수업 이름은 100자 이하로 입력해 주세요"));
	}

	@Test
	void listShowsOnlyMyClassroomsNewestFirst() throws Exception {
		String tokenA = teacherToken("목록교사가", "class-list-a@example.com");
		String tokenB = teacherToken("목록교사나", "class-list-b@example.com");
		createClassroom(tokenA, "A-1").andExpect(status().isCreated());
		createClassroom(tokenB, "B-1").andExpect(status().isCreated());
		createClassroom(tokenA, "A-2").andExpect(status().isCreated());

		listClassrooms(tokenA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].name").value("A-2"))
				.andExpect(jsonPath("$[1].name").value("A-1"));
		listClassrooms(tokenB)
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("B-1"));
	}

	@Test
	void studentTokenIsForbiddenAndNoTokenIsUnauthorized() throws Exception {
		String studentToken = postForJson("/api/auth/signup", Map.of("classCode", fixture.newClassroom().getCode(), "name", "수업학생", "phoneLast4", "1234"))
				.get("accessToken").asText();

		listClassrooms(studentToken).andExpect(status().isForbidden());
		createClassroom(studentToken, "학생이 만든 수업").andExpect(status().isForbidden());
		mockMvc.perform(MockMvcRequestBuilders.get("/api/teacher/classrooms"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void refreshedAccessTokenStillWorks() throws Exception {
		JsonNode tokens = teacherTokens("리프교사", "class-refresh@example.com");
		String refreshed = postForJson("/api/auth/refresh", Map.of("refreshToken", tokens.get("refreshToken").asText()))
				.get("accessToken").asText();

		createClassroom(refreshed, "리프 수업").andExpect(status().isCreated());
	}
}
