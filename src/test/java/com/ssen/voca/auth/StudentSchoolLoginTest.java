package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

// 학교 범위의 식별·시도 제한을 검증한다. 테스트마다 새 학교를 쓰므로 제한 키가 테스트끼리 겹치지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentSchoolLoginTest {

	private static final String BAD_CREDENTIALS = "학교, 이름 또는 휴대폰 번호가 올바르지 않아요.";
	private static final String SCHOOL_REQUIRED = "학교를 입력해 주세요";
	private static final String SCHOOL_TOO_LONG = "학교 이름은 100자 이하로 입력해 주세요";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String school;
	private String otherSchool;

	@BeforeEach
	void newSchools() {
		String suffix = UUID.randomUUID().toString();
		school = "학교가-" + suffix;
		otherSchool = "학교나-" + suffix;
	}

	private ResultActions auth(String path, String school, String name, String phoneLast4) throws Exception {
		return mockMvc.perform(post(path)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
						Map.of("school", school, "name", name, "phoneLast4", phoneLast4))));
	}

	private ResultActions raw(String path, String json) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private String accessToken(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString())
				.get("accessToken").asText();
	}

	private String subject(ResultActions actions) throws Exception {
		String payload = new String(Base64.getUrlDecoder().decode(accessToken(actions).split("\\.")[1]));
		return objectMapper.readTree(payload).get("sub").asText();
	}

	@Test
	void sameSchoolNameAndPinIsConflictButDifferentPinIsASeparateAccount() throws Exception {
		String first = subject(auth("/api/auth/signup", school, "학교동명", "1111").andExpect(status().isCreated()));
		auth("/api/auth/signup", school, "학교동명", "1111").andExpect(status().isConflict());
		String second = subject(auth("/api/auth/signup", school, "학교동명", "2222").andExpect(status().isCreated()));

		assertThat(first).isNotEqualTo(second);
		assertThat(subject(auth("/api/auth/login", school, "학교동명", "1111").andExpect(status().isOk())))
				.isEqualTo(first);
		assertThat(subject(auth("/api/auth/login", school, "학교동명", "2222").andExpect(status().isOk())))
				.isEqualTo(second);
	}

	@Test
	void anotherStudentsPinDoesNotLogInTheOtherAccount() throws Exception {
		auth("/api/auth/signup", school, "학교상대번호", "1111").andExpect(status().isCreated());
		auth("/api/auth/signup", school, "학교상대번호", "2222").andExpect(status().isCreated());

		auth("/api/auth/login", school, "학교상대번호", "3333")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(BAD_CREDENTIALS));
	}

	@Test
	void sameNameAndPinInDifferentSchoolsAreSeparateAccounts() throws Exception {
		String inA = subject(auth("/api/auth/signup", school, "학교교차", "1234").andExpect(status().isCreated()));
		String inB = subject(auth("/api/auth/signup", otherSchool, "학교교차", "1234").andExpect(status().isCreated()));

		assertThat(inA).isNotEqualTo(inB);
		assertThat(subject(auth("/api/auth/login", school, "학교교차", "1234").andExpect(status().isOk())))
				.isEqualTo(inA);
		assertThat(subject(auth("/api/auth/login", otherSchool, "학교교차", "1234").andExpect(status().isOk())))
				.isEqualTo(inB);
	}

	@Test
	void lockoutInOneSchoolDoesNotAffectTheSameNameInAnother() throws Exception {
		auth("/api/auth/signup", school, "학교잠금", "1234").andExpect(status().isCreated());
		auth("/api/auth/signup", otherSchool, "학교잠금", "1234").andExpect(status().isCreated());
		for (int i = 0; i < 4; i++) {
			auth("/api/auth/login", school, "학교잠금", "0000").andExpect(status().isUnauthorized());
		}

		// 첫 학교: 가입 1 + 실패 4 = 5회 → 정답이어도 429
		auth("/api/auth/login", school, "학교잠금", "1234").andExpect(status().isTooManyRequests());
		// 다른 학교는 가입 1회뿐이라 정상 로그인된다.
		auth("/api/auth/login", otherSchool, "학교잠금", "1234").andExpect(status().isOk());
	}

	@Test
	void schoolSpellingVariantsAreTheSameSchool() throws Exception {
		String base = "동국대부속고" + UUID.randomUUID();
		String spaced = "  동국대  부속고" + base.substring("동국대부속고".length()) + " ";
		auth("/api/auth/signup", spaced, "학교표기", "1234").andExpect(status().isCreated());

		auth("/api/auth/login", base, "학교표기", "1234").andExpect(status().isOk());
		auth("/api/auth/signup", spaced, "학교표기", "1234").andExpect(status().isConflict());
		auth("/api/auth/signup", base, "학교표기", "1234").andExpect(status().isConflict());
	}

	@Test
	void schoolIsCaseInsensitive() throws Exception {
		String id = UUID.randomUUID().toString();
		auth("/api/auth/signup", "Seoul High " + id, "학교대소문자", "1234").andExpect(status().isCreated());

		auth("/api/auth/login", "seoulhigh" + id, "학교대소문자", "1234").andExpect(status().isOk());
		auth("/api/auth/signup", "SEOUL HIGH " + id, "학교대소문자", "1234").andExpect(status().isConflict());
	}

	@Test
	void unknownSchoolLoginLooksLikeAWrongPinAndIsThrottled() throws Exception {
		auth("/api/auth/signup", school, "학교존재", "1234").andExpect(status().isCreated());
		String wrongPinBody = auth("/api/auth/login", school, "학교존재", "9999")
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();

		for (int i = 0; i < 5; i++) {
			String body = auth("/api/auth/login", otherSchool, "학교존재", "1234")
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.message").value(BAD_CREDENTIALS))
					.andReturn().getResponse().getContentAsString();
			assertThat(body).isEqualTo(wrongPinBody);
		}
		auth("/api/auth/login", otherSchool, "학교존재", "1234").andExpect(status().isTooManyRequests());
	}

	@Test
	void successfulLoginRefundsExactlyOne() throws Exception {
		auth("/api/auth/signup", school, "학교환불", "1234").andExpect(status().isCreated());
		for (int i = 0; i < 3; i++) {
			auth("/api/auth/login", school, "학교환불", "1234").andExpect(status().isOk());
		}

		// 성공한 로그인은 순수 0회 소모라 카운터는 가입 1회뿐이다. 실패 4번을 채우면 막힌다.
		for (int i = 0; i < 4; i++) {
			auth("/api/auth/login", school, "학교환불", "0000").andExpect(status().isUnauthorized());
		}
		auth("/api/auth/login", school, "학교환불", "1234").andExpect(status().isTooManyRequests());
	}

	@Test
	void blankSchoolIsRejectedWithoutUsingTheBudget() throws Exception {
		for (String blank : new String[] {"", "   ", "​", "　​﻿ ⁠"}) {
			for (String path : new String[] {"/api/auth/signup", "/api/auth/login"}) {
				// 같은 요청을 6번 보내도 429가 아니라 계속 400이다.
				for (int i = 0; i < 6; i++) {
					auth(path, blank, "학교빈값", "1234")
							.andExpect(status().isBadRequest())
							.andExpect(jsonPath("$.message").value(SCHOOL_REQUIRED));
				}
			}
		}
	}

	@Test
	void tooLongSchoolIsRejected() throws Exception {
		for (String path : new String[] {"/api/auth/signup", "/api/auth/login"}) {
			auth(path, "가".repeat(101), "학교길이", "1234")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value(SCHOOL_TOO_LONG));
		}
		auth("/api/auth/signup", "가".repeat(100), "학교길이", "1234").andExpect(status().isCreated());
	}

	@Test
	void schoolValidationComesBeforeNameAndPhone() throws Exception {
		for (String path : new String[] {"/api/auth/signup", "/api/auth/login"}) {
			raw(path, "{\"school\":\"\",\"name\":\"\",\"phoneLast4\":\"abc\"}")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value(SCHOOL_REQUIRED));
			raw(path, "{\"name\":\"학교검증\",\"phoneLast4\":\"1234\"}")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value(SCHOOL_REQUIRED));
			raw(path, "{\"school\":\"" + "A".repeat(101) + "\",\"name\":\"\",\"phoneLast4\":\"abc\"}")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value(SCHOOL_TOO_LONG));
			raw(path, "{\"school\":\"" + school + "\",\"name\":\"\",\"phoneLast4\":\"abc\"}")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value("이름을 입력해 주세요"));
		}
	}

	@Test
	void classCodeIsIgnored() throws Exception {
		raw("/api/auth/signup", "{\"classCode\":\"ZZZZZZ\",\"school\":\"" + school
				+ "\",\"name\":\"학교코드무시\",\"phoneLast4\":\"1234\"}")
				.andExpect(status().isCreated());
		raw("/api/auth/login", "{\"classCode\":\"ABCDEF\",\"school\":\"" + school
				+ "\",\"name\":\"학교코드무시\",\"phoneLast4\":\"1234\"}")
				.andExpect(status().isOk());
	}

	@Test
	void signupReturnsTokensAndMeShowsTheDisplaySchoolWithoutClassroom() throws Exception {
		String messy = "  서울   고등학교 " + UUID.randomUUID() + "  ";
		String clean = messy.trim().replaceAll("\\s+", " ");

		ResultActions signup = auth("/api/auth/signup", messy, "학교내정보", "1234")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty());

		mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken(signup)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.school").value(clean))
				.andExpect(jsonPath("$.classroom").value(nullValue()));
	}
}
