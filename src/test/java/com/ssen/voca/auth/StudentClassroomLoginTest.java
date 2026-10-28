package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssen.voca.classroom.Classroom;
import com.ssen.voca.support.ClassroomFixture;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

// 수업 범위로 좁아진 식별·시도 제한을 검증한다. 수업(참여코드)마다 새로 만들므로 제한 키가 테스트끼리 겹치지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentClassroomLoginTest {

	private static final String BAD_CREDENTIALS = "참여 코드, 이름 또는 휴대폰 번호가 올바르지 않아요.";
	private static final String UNKNOWN_CODE = "참여 코드를 확인해 주세요.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ClassroomFixture fixture;

	@Autowired
	private AppUserRepository appUserRepository;

	private ResultActions auth(String path, String classCode, String name, String phoneLast4) throws Exception {
		return mockMvc.perform(post(path)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
						Map.of("classCode", classCode, "name", name, "phoneLast4", phoneLast4))));
	}

	private String subject(ResultActions actions) throws Exception {
		String body = actions.andReturn().getResponse().getContentAsString();
		String jwt = objectMapper.readTree(body).get("accessToken").asText();
		String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
		return objectMapper.readTree(payload).get("sub").asText();
	}

	@Test
	void sameNameAndPinInDifferentClassroomsAreSeparateAccounts() throws Exception {
		Classroom a = fixture.newClassroom();
		Classroom b = fixture.newClassroom();

		String inA = subject(auth("/api/auth/signup", a.getCode(), "수업동명", "1234").andExpect(status().isCreated()));
		String inB = subject(auth("/api/auth/signup", b.getCode(), "수업동명", "1234").andExpect(status().isCreated()));

		assertThat(inA).isNotEqualTo(inB);
		assertThat(subject(auth("/api/auth/login", a.getCode(), "수업동명", "1234").andExpect(status().isOk())))
				.isEqualTo(inA);
		assertThat(subject(auth("/api/auth/login", b.getCode(), "수업동명", "1234").andExpect(status().isOk())))
				.isEqualTo(inB);
		auth("/api/auth/signup", a.getCode(), "수업동명", "1234").andExpect(status().isConflict());
	}

	@Test
	void signupStoresTheClassroomOnTheStudent() throws Exception {
		Classroom classroom = fixture.newClassroom();

		auth("/api/auth/signup", classroom.getCode(), "수업저장", "1234").andExpect(status().isCreated());

		assertThat(appUserRepository.findAllByClassroomIdAndNameKeyAndRole(
				classroom.getId(), "수업저장", AppUser.STUDENT)).hasSize(1);
	}

	@Test
	void studentCannotLoginWithAnotherClassroomsCode() throws Exception {
		Classroom a = fixture.newClassroom();
		Classroom b = fixture.newClassroom();
		auth("/api/auth/signup", a.getCode(), "수업교차", "1234").andExpect(status().isCreated());

		auth("/api/auth/login", b.getCode(), "수업교차", "1234")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(BAD_CREDENTIALS));
	}

	@Test
	void lockoutInOneClassroomDoesNotAffectTheSameNameInAnother() throws Exception {
		Classroom a = fixture.newClassroom();
		Classroom b = fixture.newClassroom();
		auth("/api/auth/signup", a.getCode(), "수업잠금", "1234").andExpect(status().isCreated());
		auth("/api/auth/signup", b.getCode(), "수업잠금", "1234").andExpect(status().isCreated());
		for (int i = 0; i < 4; i++) {
			auth("/api/auth/login", a.getCode(), "수업잠금", "0000").andExpect(status().isUnauthorized());
		}

		// A 수업: 가입 1 + 실패 4 = 5회 → 정답이어도 429
		auth("/api/auth/login", a.getCode(), "수업잠금", "1234").andExpect(status().isTooManyRequests());
		// B 수업은 가입 1회뿐이라 정상 로그인된다.
		auth("/api/auth/login", b.getCode(), "수업잠금", "1234").andExpect(status().isOk());
	}

	@Test
	void classCodeIsCaseAndWhitespaceInsensitive() throws Exception {
		Classroom classroom = fixture.newClassroom();
		auth("/api/auth/signup", classroom.getCode(), "수업대소문자", "1234").andExpect(status().isCreated());

		auth("/api/auth/login", "  " + classroom.getCode().toLowerCase() + " ", "수업대소문자", "1234")
				.andExpect(status().isOk());
	}

	@Test
	void unknownOrMalformedCodesAreRejectedOnSignup() throws Exception {
		for (String code : new String[] {"ZZZZZZ", "I0O1L1", "ABC23", "ABC2345", "가나다라마바"}) {
			auth("/api/auth/signup", code, "수업없는코드", "1234")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value(UNKNOWN_CODE));
		}
	}

	@Test
	void unknownCodeLoginNeverConsumesTheBudget() throws Exception {
		// 시도 제한을 만들었다면 6번째부터 429가 된다. 없는 코드는 카운터를 만들지 않으므로 계속 같은 401이다.
		for (int i = 0; i < 10; i++) {
			auth("/api/auth/login", "ZZZZZZ", "수업없는로그인", "1234")
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.message").value(BAD_CREDENTIALS));
		}
	}

	@Test
	void classCodeValidationComesFirst() throws Exception {
		mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
						.content("{\"classCode\":\"\",\"name\":\"\",\"phoneLast4\":\"abc\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("참여 코드를 입력해 주세요"));
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"수업검증\",\"phoneLast4\":\"1234\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("참여 코드를 입력해 주세요"));
		mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
						.content("{\"classCode\":\"" + "A".repeat(21) + "\",\"name\":\"수업검증\",\"phoneLast4\":\"1234\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(UNKNOWN_CODE));
	}
}
