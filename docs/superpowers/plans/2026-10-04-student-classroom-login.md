# 학생 로그인 참여코드 추가 (조각 2, 서버) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 학생 가입·로그인이 참여코드 + 이름 + 휴대폰 뒤 4자리로 동작하고, 학생이 수업에 속하며, 시도 제한과 중복 판별이 수업 범위로 좁아진다.

**Architecture:** `app_user.classroom_id`로 학생을 수업에 묶는다. 가입·로그인은 먼저 참여코드로 수업을 찾고(없으면 시도 제한을 건드리지 않고 거절), 제한 키는 `student:<수업 id>:<정규화한 이름>`이다. 마이그레이션은 두 단계로 나눠 중간 상태에서도 테스트가 계속 통과한다: V5(컬럼 추가) → 가입·로그인 변경 → V6(CHECK 제약 추가 + `class_code` 제거).

**Tech Stack:** Spring Boot 3.5.9, Java 21, Gradle, PostgreSQL 16, Flyway, Spring Security(stateless), JPA(`ddl-auto: validate`), JUnit 5 + MockMvc(`@SpringBootTest`, 실제 Postgres).

**Spec:** `docs/superpowers/specs/2026-09-30-classroom-wordbooks-design.md` (조각 2). 스펙과 달라지는 점은 Task 3에서 스펙 문서에 반영한다: `GET /api/classroom/words`는 단어 테이블이 생기는 1b 이후(조각 3)로 미루고, 이번에는 `/api/users/me`의 `classroom`으로 소속 수업을 돌려준다.

## Global Constraints

- 작업 위치: `/Users/JM/dev/ssen/ssen-server`, 브랜치 `feature/13-student-classroom-login`(이슈 #13). 코드 들여쓰기는 **탭**.
- 테스트는 항상 `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --rerun`(캐시된 실행은 증거가 아니다). DB 컨테이너(`ssen-db`)가 떠 있어야 한다. 8080/8081의 다른 프로세스는 건드리지 않는다.
- 요청 JSON 필드: `classCode`, `name`, `phoneLast4`. 검증 오류 필드 순서는 `classCode` → `name` → `phoneLast4`(교사 필드는 기존 순서 유지).
- **참여코드 정규화**: 앞뒤 공백 제거 → 영문 대문자화 → `ABCDEFGHJKMNPQRSTUVWXYZ23456789` 6자가 아니면 "없는 코드"로 본다(DB 조회도 하지 않는다).
- **없는 코드**: 가입은 400 `{"message":"참여 코드를 확인해 주세요."}`, 로그인은 일반 401(코드 존재 여부를 드러내지 않음)이며 BCrypt 비용 1회를 치른다. **두 경우 모두 시도 제한 카운터를 만들거나 늘리지 않는다**(공격자가 값을 바꿔 가며 제한 목록을 채우지 못하게).
- 시도 제한 키: `student:<수업 id>:<Names.key 결과>`. 선차감·성공 로그인 시 1회 환불 규칙은 그대로다. 교사 키(`teacher-signup`, `teacher:<이메일>`)는 건드리지 않는다.
- 로그인 실패 메시지(401): `참여 코드, 이름 또는 휴대폰 번호가 올바르지 않아요.` (교사 로그인의 메시지는 그대로).
- 커밋 메시지에 **AI attribution을 넣지 않는다**(`Co-Authored-By:`, `Generated with`, 로봇 이모지). 이 조직의 `AGENTS.md`와 GitHub Ruleset이 막는다. 커밋 제목은 한국어 `feat: ... (#13)`.
- 범위 밖: 단어 조회 API(`/api/classroom/words`), 수업 수정·삭제, 학생 수업 이동, 앱 변경(별도 저장소·이슈).

## Review Focus

1. **없는 참여코드 로그인**: 몇 번을 보내도 429가 되지 않고(제한 카운터를 만들지 않음) 항상 같은 401이다. → Task 2 `StudentClassroomLoginTest.unknownCodeLoginNeverConsumesTheBudget`.
2. **같은 이름 + 같은 번호가 서로 다른 수업에**: 각자 가입되고 로그인은 자기 계정으로만 되며, 한 수업의 잠금이 다른 수업에 번지지 않는다. → `StudentClassroomLoginTest`.
3. **참여코드 대소문자·공백 변형**(`" abc234 "`): 같은 수업으로 취급한다. 형식이 틀린 코드(`I0O1L1`, 7자)는 DB를 조회하지 않고 "없는 코드"다. → `StudentClassroomLoginTest`.
4. **수업이 없던 옛 학생 행**(`classroom_id` NULL): 새 로그인 경로에서는 어디에도 매칭되지 않지만 `/api/users/me`는 깨지지 않고 `classroom: null`을 돌려준다. → Task 3 `UserControllerTest`.
5. **교사와 같은 이름·같은 수업 코드로 학생 로그인**: 교사 행은 절대 매칭되지 않는다(역할 조건). → 기존 `TeacherAuthControllerTest.teacherNameDoesNotAffectStudents`(수정 후에도 통과).
6. `class_code` 열 제거와 CHECK 제약 후에도 컨텍스트가 `validate`로 뜬다, 수업 없는 학생 행 삽입은 DB가 거절한다. → Task 3 `StudentSchemaTest`.

---

### Task 1: 학생의 수업 소속 컬럼, 조회 쿼리, 테스트 픽스처

**Files:**
- Create: `src/main/resources/db/migration/V5__student_classroom.sql`
- Modify: `src/main/java/com/ssen/voca/user/AppUser.java`, `src/main/java/com/ssen/voca/user/AppUserRepository.java`, `src/main/java/com/ssen/voca/classroom/ClassroomRepository.java`
- Create: `src/test/java/com/ssen/voca/support/ClassroomFixture.java`
- Test: `src/test/java/com/ssen/voca/user/StudentClassroomRepositoryTest.java`

**Interfaces:**
- Produces: `AppUser.getClassroomId(): Long`, 생성자 `AppUser(String name, String nameKey, String secretHash, Long classroomId)`(기존 3인자 생성자는 `classroomId = null`로 위임하며 Task 3에서 제거), `AppUserRepository.findAllByClassroomIdAndNameKeyAndRole(Long classroomId, String nameKey, String role): List<AppUser>`, `ClassroomRepository.findByCode(String code): Optional<Classroom>`. 테스트 지원: `ClassroomFixture.newClassroom(): Classroom`(새 교사와 그 교사의 새 수업을 저장), `ClassroomFixture.deleteClassroomAndTeacher(Classroom)`(트랜잭션 없는 테스트의 뒷정리용).

- [ ] **Step 1: 실패하는 테스트와 픽스처 작성**

`src/test/java/com/ssen/voca/support/ClassroomFixture.java` (테스트 소스지만 `com.ssen.voca` 아래라서 모든 `@SpringBootTest` 컨텍스트가 컴포넌트로 가져간다)

```java
package com.ssen.voca.support;

import com.ssen.voca.classroom.ClassCodeGenerator;
import com.ssen.voca.classroom.Classroom;
import com.ssen.voca.classroom.ClassroomRepository;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** 테스트용 교사와 수업을 만든다. 호출할 때마다 서로 다른 교사 이메일·참여코드가 나온다. */
@Component
public class ClassroomFixture {

	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	private final AppUserRepository appUserRepository;
	private final ClassroomRepository classroomRepository;
	private final ClassCodeGenerator codeGenerator;

	public ClassroomFixture(
			AppUserRepository appUserRepository,
			ClassroomRepository classroomRepository,
			ClassCodeGenerator codeGenerator) {
		this.appUserRepository = appUserRepository;
		this.classroomRepository = classroomRepository;
		this.codeGenerator = codeGenerator;
	}

	public Classroom newClassroom() {
		int n = SEQUENCE.incrementAndGet();
		AppUser teacher = appUserRepository.save(AppUser.teacher(
				"픽스처교사" + n, "픽스처교사" + n, "fixture-" + UUID.randomUUID() + "@example.com", "hash"));
		String code;
		do {
			code = codeGenerator.generate();
		} while (classroomRepository.existsByCode(code));
		return classroomRepository.save(new Classroom(teacher.getId(), "픽스처수업" + n, code));
	}

	/** 트랜잭션 밖에서 만든 수업과 교사를 지운다 (그 수업에 속한 학생을 먼저 지운 뒤 호출한다). */
	public void deleteClassroomAndTeacher(Classroom classroom) {
		classroomRepository.deleteById(classroom.getId());
		appUserRepository.deleteById(classroom.getTeacherId());
	}
}
```

`src/test/java/com/ssen/voca/user/StudentClassroomRepositoryTest.java`

```java
package com.ssen.voca.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssen.voca.classroom.Classroom;
import com.ssen.voca.classroom.ClassroomRepository;
import com.ssen.voca.support.ClassroomFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class StudentClassroomRepositoryTest {

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private ClassroomRepository classroomRepository;

	@Autowired
	private ClassroomFixture fixture;

	@Test
	void findByCodeReturnsTheClassroomAndEmptyForUnknownCodes() {
		Classroom classroom = fixture.newClassroom();

		assertThat(classroomRepository.findByCode(classroom.getCode()))
				.get().extracting(Classroom::getId).isEqualTo(classroom.getId());
		assertThat(classroomRepository.findByCode("ZZZZZZ")).isEmpty();
	}

	@Test
	void studentLookupIsScopedToTheClassroomAndTheStudentRole() {
		Classroom a = fixture.newClassroom();
		Classroom b = fixture.newClassroom();
		appUserRepository.saveAndFlush(new AppUser("가", "수업조회동명", "h", a.getId()));
		appUserRepository.saveAndFlush(new AppUser("나", "수업조회동명", "h", b.getId()));
		// 같은 이름 키의 교사는 학생 조회에 섞이지 않는다.
		appUserRepository.saveAndFlush(AppUser.teacher("교사", "수업조회동명", "scoped-teacher@example.com", "h"));

		assertThat(appUserRepository.findAllByClassroomIdAndNameKeyAndRole(a.getId(), "수업조회동명", AppUser.STUDENT))
				.extracting(AppUser::getName).containsExactly("가");
		assertThat(appUserRepository.findAllByClassroomIdAndNameKeyAndRole(b.getId(), "수업조회동명", AppUser.STUDENT))
				.extracting(AppUser::getName).containsExactly("나");
		assertThat(appUserRepository.findAllByClassroomIdAndNameKeyAndRole(a.getId(), "다른이름", AppUser.STUDENT))
				.isEmpty();
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --tests '*StudentClassroomRepositoryTest'`
Expected: 컴파일 FAIL (`findByCode`, 4인자 `AppUser` 생성자, `findAllByClassroomIdAndNameKeyAndRole` 없음).

- [ ] **Step 3: 마이그레이션 작성** — `src/main/resources/db/migration/V5__student_classroom.sql`

```sql
-- 조각 2: 학생이 속한 수업. CHECK 제약과 class_code 제거는 가입·로그인이 바뀐 뒤(V6)에 한다.
ALTER TABLE app_user ADD COLUMN classroom_id BIGINT REFERENCES classroom (id);
CREATE INDEX idx_app_user_classroom_name ON app_user (classroom_id, name_key);
```

- [ ] **Step 4: 엔티티와 저장소 수정**

`AppUser.java` — 필드와 생성자를 더한다(나머지 필드·메서드는 그대로).

```java
	@Column(name = "classroom_id")
	private Long classroomId;
```
(`classCode` 필드 아래에 추가)

```java
	public AppUser(String name, String nameKey, String secretHash, Long classroomId) {
		this(name, nameKey, secretHash);
		this.classroomId = classroomId;
	}
```
(기존 3인자 생성자 아래에 추가)

`AppUserRepository.java`에 추가:

```java
	List<AppUser> findAllByClassroomIdAndNameKeyAndRole(Long classroomId, String nameKey, String role);
```

`ClassroomRepository.java`에 추가(import `java.util.Optional`):

```java
	Optional<Classroom> findByCode(String code);
```

- [ ] **Step 5: 전체 테스트 통과 확인**

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --rerun`
Expected: BUILD SUCCESSFUL, 기존 110개 + 신규 2개 통과(`validate`가 `classroom_id` 매핑을 통과해야 한다).

- [ ] **Step 6: 커밋**

```bash
git add src/main src/test
git commit -m "feat: 학생의 수업 소속 컬럼과 수업별 학생 조회 쿼리 추가 (#13)"
```

---

### Task 2: 가입·로그인에 참여코드 (수업 범위 식별과 시도 제한)

**Files:**
- Create: `src/main/java/com/ssen/voca/classroom/ClassCodes.java`, `src/main/java/com/ssen/voca/classroom/ClassroomNotFoundException.java`
- Modify: `src/main/java/com/ssen/voca/classroom/ClassCodeGenerator.java`, `src/main/java/com/ssen/voca/auth/dto/SignupRequest.java`, `src/main/java/com/ssen/voca/auth/dto/LoginRequest.java`, `src/main/java/com/ssen/voca/auth/AuthService.java`, `src/main/java/com/ssen/voca/auth/InvalidCredentialsException.java`, `src/main/java/com/ssen/voca/common/GlobalExceptionHandler.java`
- Modify(기존 테스트): `AuthServiceTest`, `AuthControllerTest`, `AuthThrottleTest`, `AuthConcurrencyTest`, `TeacherAuthControllerTest`, `TeacherThrottleTest`, `TeacherClassroomControllerTest`, `UserControllerTest`
- Test: `src/test/java/com/ssen/voca/auth/StudentClassroomLoginTest.java`, `src/test/java/com/ssen/voca/classroom/ClassCodesTest.java`

**Interfaces:**
- Consumes: `ClassroomRepository.findByCode`, `AppUserRepository.findAllByClassroomIdAndNameKeyAndRole`, 4인자 `AppUser` 생성자, `ClassroomFixture` (Task 1)
- Produces: `SignupRequest(String classCode, String name, String phoneLast4)`, `LoginRequest(String classCode, String name, String phoneLast4)`(두 record 모두 `classCode`가 **첫 번째** 컴포넌트), `ClassCodes.ALPHABET`/`LENGTH`/`normalize(String): String`(형식이 틀리면 `null`), `ClassroomNotFoundException`(400, 메시지 `참여 코드를 확인해 주세요.`), `InvalidCredentialsException()` 기본 메시지 `참여 코드, 이름 또는 휴대폰 번호가 올바르지 않아요.`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/ssen/voca/classroom/ClassCodesTest.java`

```java
package com.ssen.voca.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClassCodesTest {

	@Test
	void normalizeStripsAndUpperCases() {
		assertThat(ClassCodes.normalize("abc234")).isEqualTo("ABC234");
		assertThat(ClassCodes.normalize("  Abc234 \n")).isEqualTo("ABC234");
		assertThat(ClassCodes.normalize("　ABC234　")).isEqualTo("ABC234");
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {
			"", "ABC23", "ABC2345", "ABC 234", "I0O1L1", "ABC23!", "가나다라마바"})
	void normalizeReturnsNullForInvalidFormats(String raw) {
		assertThat(ClassCodes.normalize(raw)).isNull();
	}
}
```
(`spring-boot-starter-test`에 JUnit params가 포함돼 있다.)

`src/test/java/com/ssen/voca/auth/StudentClassroomLoginTest.java`

```java
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
```

- [ ] **Step 2: 실패 확인**

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --tests '*ClassCodesTest' --tests '*StudentClassroomLoginTest'`
Expected: 컴파일 FAIL (`ClassCodes` 없음). 기존 테스트의 `new SignupRequest(name, pin)` 호출도 다음 단계 후 컴파일 오류가 나므로 Step 5에서 함께 고친다.

- [ ] **Step 3: 참여코드 유틸과 예외, DTO 작성**

`classroom/ClassCodes.java`:

```java
package com.ssen.voca.classroom;

import java.util.Locale;
import java.util.regex.Pattern;

/** 참여코드의 글자 집합과 입력 정규화. 0/O, 1/I/L처럼 헷갈리는 글자는 코드에 쓰지 않는다. */
public final class ClassCodes {

	public static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
	public static final int LENGTH = 6;

	private static final Pattern FORMAT = Pattern.compile("[" + ALPHABET + "]{" + LENGTH + "}");

	private ClassCodes() {
	}

	/** 앞뒤 공백을 제거하고 대문자로 바꾼다. 코드 형식이 아니면 null (DB를 조회할 필요가 없는 입력). */
	public static String normalize(String raw) {
		String code = raw.strip().toUpperCase(Locale.ROOT);
		return FORMAT.matcher(code).matches() ? code : null;
	}
}
```

`ClassCodeGenerator.java`에서 상수 정의를 지우고 `ClassCodes`를 쓰게 한다.

```java
// 삭제: static final String ALPHABET = ...; static final int LENGTH = 6;
	public String generate() {
		StringBuilder code = new StringBuilder(ClassCodes.LENGTH);
		for (int i = 0; i < ClassCodes.LENGTH; i++) {
			code.append(ClassCodes.ALPHABET.charAt(random.nextInt(ClassCodes.ALPHABET.length())));
		}
		return code.toString();
	}
```

`classroom/ClassroomNotFoundException.java`:

```java
package com.ssen.voca.classroom;

/** 가입할 때 입력한 참여코드에 해당하는 수업이 없을 때. */
public class ClassroomNotFoundException extends RuntimeException {

	public ClassroomNotFoundException() {
		super("참여 코드를 확인해 주세요.");
	}
}
```

`auth/dto/SignupRequest.java` 전체:

```java
package com.ssen.voca.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
		@NotBlank(message = "참여 코드를 입력해 주세요")
		@Size(max = 20, message = "참여 코드를 확인해 주세요.")
		String classCode,
		@NotBlank(message = "이름을 입력해 주세요")
		@Size(max = 50, message = "이름은 50자 이하로 입력해 주세요")
		String name,
		@Pattern(regexp = "\\d{4}", message = "휴대폰 번호 뒤 4자리를 숫자로 입력해 주세요")
		String phoneLast4) {
}
```
(기존 파일의 name/phoneLast4 어노테이션 메시지는 그대로 두고 `classCode`만 앞에 더하는 것이다. 기존 파일과 다르게 보이면 기존 어노테이션을 우선한다.)

`auth/dto/LoginRequest.java`도 같은 방식으로 맨 앞에 `classCode`를 더한다(`@NotBlank(message = "참여 코드를 입력해 주세요") @Size(max = 20, message = "참여 코드를 확인해 주세요.") String classCode`). 기존 `name`, `phoneLast4` 어노테이션은 그대로.

`InvalidCredentialsException.java`의 기본 생성자 메시지를 `참여 코드, 이름 또는 휴대폰 번호가 올바르지 않아요.`로 바꾼다(두 번째 생성자 `(String message)`는 그대로).

- [ ] **Step 4: AuthService 교체**

`src/main/java/com/ssen/voca/auth/AuthService.java` 전체를 다음으로 교체한다.

```java
package com.ssen.voca.auth;

import com.ssen.voca.auth.dto.AccessTokenResponse;
import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.RefreshRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import com.ssen.voca.classroom.ClassCodes;
import com.ssen.voca.classroom.Classroom;
import com.ssen.voca.classroom.ClassroomNotFoundException;
import com.ssen.voca.classroom.ClassroomRepository;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	// ponytail: dummy BCrypt hash so an unknown class code / name still pays the encoder cost,
	// keeping login timing similar regardless of account existence.
	private static final String DUMMY_PIN_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
	// 학생 제한 키의 접두사. 교사 키(teacher-signup, teacher:<이메일>)와 겹치지 않게 한다.
	private static final String THROTTLE_PREFIX = "student:";

	private final AppUserRepository appUserRepository;
	private final ClassroomRepository classroomRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final LoginAttemptLimiter attemptLimiter;

	public AuthService(
			AppUserRepository appUserRepository,
			ClassroomRepository classroomRepository,
			PasswordEncoder passwordEncoder,
			JwtService jwtService,
			LoginAttemptLimiter attemptLimiter) {
		this.appUserRepository = appUserRepository;
		this.classroomRepository = classroomRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.attemptLimiter = attemptLimiter;
	}

	@Transactional
	public TokenResponse signup(SignupRequest request) {
		String nameKey = Names.key(request.name());
		// 수업을 먼저 찾는다. 없는 코드는 시도 제한 카운터를 만들지 않고 거절한다.
		Classroom classroom = findClassroom(request.classCode());
		if (classroom == null) {
			throw new ClassroomNotFoundException();
		}
		String key = throttleKey(classroom, nameKey);
		// 가입 호출은 성공 여부와 무관하게 시도 1회로 센다 (PIN 탐색용으로 쓰일 수 있으므로).
		attemptLimiter.tryAcquire(key);
		// DB 유니크 제약은 해시된 PIN에 걸 수 없어서 같은 수업·같은 이름의 계정을 모두 BCrypt 비교한다.
		// ponytail: 동시에 같은 (수업, 이름, PIN)으로 가입하면 중복이 생길 수 있는 경합은 허용 — 필요하면 advisory lock.
		List<AppUser> sameName = appUserRepository.findAllByClassroomIdAndNameKeyAndRole(
				classroom.getId(), nameKey, AppUser.STUDENT);
		if (findMatch(sameName, request.phoneLast4()) != null) {
			throw new StudentAlreadyExistsException();
		}
		AppUser user = new AppUser(
				Names.display(request.name()), nameKey, passwordEncoder.encode(request.phoneLast4()), classroom.getId());
		appUserRepository.save(user);
		return issueTokens(user);
	}

	public TokenResponse login(LoginRequest request) {
		String nameKey = Names.key(request.name());
		Classroom classroom = findClassroom(request.classCode());
		if (classroom == null) {
			// 코드가 있는지 드러내지 않도록 같은 401과 비슷한 처리 시간을 쓰고, 제한 카운터는 만들지 않는다.
			passwordEncoder.matches(request.phoneLast4(), DUMMY_PIN_HASH);
			throw new InvalidCredentialsException();
		}
		String key = throttleKey(classroom, nameKey);
		// 선차감: 병렬 요청도 창당 한도를 넘지 못한다. 실패한 로그인은 차감을 그대로 두고, 성공하면 1회만 돌려준다.
		attemptLimiter.tryAcquire(key);
		List<AppUser> candidates = appUserRepository.findAllByClassroomIdAndNameKeyAndRole(
				classroom.getId(), nameKey, AppUser.STUDENT);
		if (candidates.isEmpty()) {
			passwordEncoder.matches(request.phoneLast4(), DUMMY_PIN_HASH);
		}
		AppUser user = findMatch(candidates, request.phoneLast4());
		if (user == null) {
			throw new InvalidCredentialsException();
		}
		attemptLimiter.release(key);
		return issueTokens(user);
	}

	public AccessTokenResponse refresh(RefreshRequest request) {
		Long userId = Long.valueOf(jwtService.parseRefreshToken(request.refreshToken()).getSubject());
		AppUser user = appUserRepository.findById(userId)
				.orElseThrow(() -> new InvalidTokenException("존재하지 않는 사용자입니다."));
		return new AccessTokenResponse(jwtService.generateAccessToken(user.getId(), user.getName(), user.getRole()));
	}

	/** 참여코드로 수업을 찾는다. 형식이 틀리거나 없는 코드면 null. */
	private Classroom findClassroom(String rawCode) {
		String code = ClassCodes.normalize(rawCode);
		return code == null ? null : classroomRepository.findByCode(code).orElse(null);
	}

	private static String throttleKey(Classroom classroom, String nameKey) {
		return THROTTLE_PREFIX + classroom.getId() + ":" + nameKey;
	}

	private AppUser findMatch(List<AppUser> candidates, String pin) {
		for (AppUser candidate : candidates) {
			if (passwordEncoder.matches(pin, candidate.getSecretHash())) {
				return candidate;
			}
		}
		return null;
	}

	private TokenResponse issueTokens(AppUser user) {
		return new TokenResponse(
				jwtService.generateAccessToken(user.getId(), user.getName(), user.getRole()),
				jwtService.generateRefreshToken(user.getId()));
	}
}
```

`GlobalExceptionHandler.java`:
- `FIELD_ORDER`를 `List.of("classCode", "name", "email", "password", "inviteCode", "phoneLast4")`로 바꾼다.
- 핸들러를 추가한다(import `com.ssen.voca.classroom.ClassroomNotFoundException`).

```java
	@ExceptionHandler(ClassroomNotFoundException.class)
	public ResponseEntity<Map<String, String>> handleClassroomNotFound(ClassroomNotFoundException e) {
		return message(HttpStatus.BAD_REQUEST, e.getMessage());
	}
```

- [ ] **Step 5: 기존 테스트를 새 계약에 맞게 수정**

공통 규칙(아래 파일들에 적용): `@Autowired private ClassroomFixture fixture;`와 `private String code;`를 더하고, `@BeforeEach void newClassroom() { code = fixture.newClassroom().getCode(); }`를 만든다(`org.junit.jupiter.api.BeforeEach`). `new SignupRequest(x, y)` / `new LoginRequest(x, y)` 호출은 모두 **`code`를 첫 인자로** 추가한다. 학생 JSON 본문(`Map.of("name", ..., "phoneLast4", ...)`)에는 `"classCode", code`를 더한다.

- `AuthServiceTest`: 규칙 그대로(모든 `SignupRequest`/`LoginRequest`에 `code`).
- `AuthThrottleTest`: 규칙 그대로. `failLogin` 헬퍼의 `new LoginRequest(name, "0000")`도 `code` 추가. 이 클래스는 `MutableClock` 전용 컨텍스트라 `@Autowired ClassroomFixture`도 그 컨텍스트에서 주입된다.
- `AuthControllerTest`: `auth(path, name, phoneLast4)` 헬퍼의 `Map.of`에 `"classCode", code`를 더한다. 상수 `BAD_CREDENTIALS`를 `"참여 코드, 이름 또는 휴대폰 번호가 올바르지 않아요."`로 바꾼다. `signupValidationErrorsReturn400WithFirstMessage`의 마지막 원문 JSON `{"name":"컨트롤러검증"}`은 `참여 코드가 없으면 첫 메시지가 달라지므로` `"{\"classCode\":\"" + code + "\",\"name\":\"컨트롤러검증\"}"`로 바꿔 원래 의도(전화번호 누락 → PIN 메시지)를 유지한다.
- `AuthConcurrencyTest`: 트랜잭션이 없는 테스트라 수업을 만들면 DB에 남는다. 아래처럼 고친다 — `@Autowired ClassroomFixture fixture;`, `private Classroom classroom;`, `@BeforeEach void newClassroom() { classroom = fixture.newClassroom(); }`, `@AfterEach void cleanUp() { fixture.deleteClassroomAndTeacher(classroom); }`, 그리고 요청 본문을 `"{\"classCode\":\"" + classroom.getCode() + "\",\"name\":\"동시성없는사람\",\"phoneLast4\":\"0000\"}"`로 바꾼다(없는 이름이지만 **실제 수업**이어야 제한 키가 생긴다). 기대값(401 5개, 429 15개)은 그대로다. 클래스 주석의 "DB에 아무것도 쓰지 않는" 문장은 "수업·교사 행은 @AfterEach에서 지운다"로 고친다.
- `TeacherAuthControllerTest.teacherNameDoesNotAffectStudents`: 학생 가입·로그인 JSON 세 곳에 `"classCode", code`(`fixture.newClassroom().getCode()`로 얻은 값)를 더한다.
- `TeacherThrottleTest`: 학생 키 충돌 테스트 두 개(`teacher-signup`, `teacher:victim@example.com` 이름)의 `new LoginRequest(...)`에 `code`를 더한다. 이 테스트들은 학생이 **없는 이름**으로 실패하는 것이므로, 수업이 있어야 제한 키가 생겨 의미가 유지된다.
- `TeacherClassroomControllerTest.studentTokenIsForbiddenAndNoTokenIsUnauthorized`: 학생 가입 JSON에 `"classCode"`(`fixture.newClassroom().getCode()`)를 더한다.
- `UserControllerTest`: `signup(name)` 헬퍼의 `new SignupRequest(name, "1234")`를 `new SignupRequest(code, name, "1234")`로 바꾼다. 참여 코드(`PATCH class-code`) 테스트들은 이 태스크에서는 그대로 둔다(Task 3에서 삭제).

- [ ] **Step 6: 전체 테스트 통과 확인**

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --rerun`
Expected: BUILD SUCCESSFUL, 모든 테스트 통과(기존 + `ClassCodesTest` + `StudentClassroomLoginTest`). 실패하면 어느 테스트가 어느 규칙을 놓쳤는지 확인해 그 테스트만 고친다.

- [ ] **Step 7: 커밋**

```bash
git add src/main src/test
git commit -m "feat: 학생 가입·로그인에 참여코드를 추가하고 식별·시도 제한을 수업 범위로 좁힘 (#13)"
```

---

### Task 3: `class_code` 제거, 내 정보에 소속 수업, CHECK 제약, 문서

**Files:**
- Create: `src/main/resources/db/migration/V6__student_classroom_required.sql`, `src/main/java/com/ssen/voca/user/dto/ClassroomSummary.java`
- Delete: `src/main/java/com/ssen/voca/user/dto/ClassCodeRequest.java`
- Modify: `src/main/java/com/ssen/voca/user/AppUser.java`, `AppUserRepository.java`, `src/main/java/com/ssen/voca/user/UserController.java`, `src/main/java/com/ssen/voca/user/dto/UserResponse.java`, `README.md`, `docs/superpowers/specs/2026-09-30-classroom-wordbooks-design.md`
- Modify(기존 테스트): `UserControllerTest`, `TeacherSchemaTest`, `AppUserRepositoryTest`, `StudentClassroomRepositoryTest`(옛 쿼리 참조가 있으면)
- Test: `src/test/java/com/ssen/voca/user/StudentSchemaTest.java`

**Interfaces:**
- Consumes: `ClassroomRepository`, `ClassroomFixture`, `SignupRequest(classCode, name, phoneLast4)` (Task 1·2)
- Produces: `GET /api/users/me` → `{id, name, role, email, classroom}`(`classroom`은 `{id, name, code}` 또는 `null`), `UserResponse(Long id, String name, String role, String email, ClassroomSummary classroom)`, `ClassroomSummary(Long id, String name, String code)`. `PATCH /api/users/me/class-code`는 사라진다(404). `AppUser`의 학생 생성자는 `AppUser(name, nameKey, secretHash, classroomId)` 하나만 남는다.

- [ ] **Step 1: 실패하는 테스트 작성/수정**

`src/test/java/com/ssen/voca/user/StudentSchemaTest.java`

```java
package com.ssen.voca.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssen.voca.classroom.Classroom;
import com.ssen.voca.support.ClassroomFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class StudentSchemaTest {

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private ClassroomFixture fixture;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void studentWithoutClassroomViolatesCheckConstraint() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO app_user (name, name_key, secret_hash, role) VALUES ('학생', '학생', 'h', 'STUDENT')"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void studentWithClassroomIsSaved() {
		Classroom classroom = fixture.newClassroom();

		AppUser saved = appUserRepository.saveAndFlush(new AppUser("학생", "스키마학생", "h", classroom.getId()));

		assertThat(saved.getClassroomId()).isEqualTo(classroom.getId());
		assertThat(saved.getRole()).isEqualTo(AppUser.STUDENT);
	}

	@Test
	void teacherDoesNotNeedAClassroom() {
		AppUser saved = appUserRepository.saveAndFlush(
				AppUser.teacher("교사", "스키마교사학생", "schema-student-teacher@example.com", "h"));

		assertThat(saved.getClassroomId()).isNull();
	}

	@Test
	void classCodeColumnIsGone() {
		Integer columns = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM information_schema.columns WHERE table_name = 'app_user' AND column_name = 'class_code'",
				Integer.class);

		assertThat(columns).isZero();
	}
}
```

`UserControllerTest`에서:
- 삭제: `updateClassCodeThenMeReflectsIt`, `patchClassCode` 헬퍼, `classCodeOverFiftyCharsReturns400WithMessage`, `classCodeOfExactlyFiftyCharsIsAccepted`, `whitespaceOnlyClassCodeReturns400`, `classCodeIsStoredTrimmedAndLengthIsCheckedOnTrimmedValue`(그리고 쓰지 않게 된 import: `patch`, `ClassCodeRequest`).
- 수정: `meWithValidTokenReturns200WithUserInfo`의 `$.email doesNotExist` 단언을 `jsonPath("$.email").value(nullValue())`로 바꾼다.
- 추가(이 클래스는 이미 `fixture`/`code` 규칙으로 Task 2에서 수업을 가진다. 수업 객체가 필요하므로 `code` 대신 `private Classroom classroom;`로 바꿔 `classroom.getCode()`를 쓴다):

```java
	@Test
	void studentMeShowsTheClassroom() throws Exception {
		String token = signupAndGetAccessToken("내정보수업");

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.classroom.id").value(classroom.getId()))
				.andExpect(jsonPath("$.classroom.name").value(classroom.getName()))
				.andExpect(jsonPath("$.classroom.code").value(classroom.getCode()))
				.andExpect(jsonPath("$.classCode").doesNotExist());
	}

	@Test
	void teacherMeHasNullClassroom() throws Exception {
		// 이 클래스의 teacherMeIncludesEmailAndRole과 같은 방식으로 교사로 가입한 토큰을 얻는다.
		String response = mockMvc.perform(post("/api/teacher/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of(
								"name", "내정보교사수업", "email", "me-teacher-classroom@example.com",
								"password", "password1", "inviteCode", inviteCode))))
				.andReturn().getResponse().getContentAsString();
		String token = objectMapper.readTree(response).get("accessToken").asText();

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(jsonPath("$.classroom").value(nullValue()));
	}

	@Test
	void legacyStudentWithoutClassroomStillGetsMe() throws Exception {
		// 수업이 없던 시절의 학생 행(V6 이전). CHECK 제약이 NOT VALID라 기존 행은 남을 수 있고, 새 토큰은 직접 만든다.
		jdbcTemplate.execute("ALTER TABLE app_user DROP CONSTRAINT ck_app_user_student_classroom");
		Long id = jdbcTemplate.queryForObject(
				"INSERT INTO app_user (name, name_key, secret_hash, role) VALUES ('옛학생', '옛학생', 'h', 'STUDENT') RETURNING id",
				Long.class);
		String token = jwtService.generateAccessToken(id, "옛학생", "STUDENT");

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("옛학생"))
				.andExpect(jsonPath("$.classroom").value(nullValue()));
	}

	@Test
	void patchingTheClassCodeIsGone() throws Exception {
		String token = signupAndGetAccessToken("내정보코드삭제");

		mockMvc.perform(patch("/api/users/me/class-code")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"classCode\":\"ABC234\"}"))
				.andExpect(status().isNotFound());
	}
```
(`patchingTheClassCodeIsGone`에서는 `patch` 정적 import를 유지한다. `legacyStudent...`는 `@Transactional` 안에서 제약을 지우므로 롤백된다. `jdbcTemplate`, `jwtService`는 `@Autowired`로 이 클래스에 더한다.)

`TeacherSchemaTest`, `AppUserRepositoryTest`, `StudentClassroomRepositoryTest`에서 `findAllByNameKeyAndRole`을 쓰는 곳은 수업이 있는 학생(`new AppUser(name, key, "h", classroom.getId())`, 수업은 `fixture.newClassroom()`)과 `findAllByClassroomIdAndNameKeyAndRole`로 바꾼다. `AppUser` 3인자 학생 생성자를 쓰던 곳도 4인자로 바꾼다(교사는 `AppUser.teacher(...)` 그대로).

- [ ] **Step 2: 실패 확인**

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --tests '*StudentSchemaTest' --tests '*UserControllerTest'`
Expected: FAIL/컴파일 오류 (`UserResponse.classroom` 없음, `class_code` 열이 아직 있음 등).

- [ ] **Step 3: 마이그레이션 작성** — `V6__student_classroom_required.sql`

```sql
-- 조각 2 마무리: 새 학생은 반드시 수업에 속하고, 참여 코드 문자열 열은 더 쓰지 않는다.
-- NOT VALID: 수업이 없던 기존(개발용) 학생 행은 검사하지 않고, 새로 쓰는 행부터 검사한다.
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_student_classroom
    CHECK (role <> 'STUDENT' OR classroom_id IS NOT NULL) NOT VALID;
ALTER TABLE app_user DROP COLUMN class_code;
```

- [ ] **Step 4: 엔티티·응답·컨트롤러 수정**

`AppUser.java`를 다음으로 교체한다.

```java
package com.ssen.voca.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor
public class AppUser {

	public static final String STUDENT = "STUDENT";
	public static final String TEACHER = "TEACHER";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String name;

	@Column(name = "name_key", nullable = false, length = 50)
	private String nameKey;

	// 학생은 휴대폰 뒤 4자리, 교사는 비밀번호의 BCrypt 해시.
	@Column(name = "secret_hash", nullable = false)
	private String secretHash;

	@Column(length = 255)
	private String email;

	// 학생이 속한 수업 (교사는 null).
	@Column(name = "classroom_id")
	private Long classroomId;

	@Column(nullable = false, length = 20)
	private String role;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private AppUser(String name, String nameKey, String secretHash, Long classroomId, String email, String role) {
		this.name = name;
		this.nameKey = nameKey;
		this.secretHash = secretHash;
		this.classroomId = classroomId;
		this.email = email;
		this.role = role;
		this.createdAt = LocalDateTime.now();
	}

	public AppUser(String name, String nameKey, String secretHash, Long classroomId) {
		this(name, nameKey, secretHash, classroomId, null, STUDENT);
	}

	public static AppUser teacher(String name, String nameKey, String email, String secretHash) {
		return new AppUser(name, nameKey, secretHash, null, email, TEACHER);
	}
}
```

`AppUserRepository.java`에서 `findAllByNameKeyAndRole(String, String)`을 삭제한다(나머지 메서드 유지).

`user/dto/ClassroomSummary.java`:

```java
package com.ssen.voca.user.dto;

public record ClassroomSummary(Long id, String name, String code) {
}
```

`user/dto/UserResponse.java` 전체:

```java
package com.ssen.voca.user.dto;

public record UserResponse(Long id, String name, String role, String email, ClassroomSummary classroom) {
}
```

`user/dto/ClassCodeRequest.java`는 삭제한다.

`user/UserController.java`를 다음으로 교체한다(기존 import 중 쓰지 않는 것은 지운다).

```java
package com.ssen.voca.user;

import com.ssen.voca.auth.InvalidTokenException;
import com.ssen.voca.classroom.ClassroomRepository;
import com.ssen.voca.user.dto.ClassroomSummary;
import com.ssen.voca.user.dto.UserResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
public class UserController {

	private final AppUserRepository appUserRepository;
	private final ClassroomRepository classroomRepository;

	public UserController(AppUserRepository appUserRepository, ClassroomRepository classroomRepository) {
		this.appUserRepository = appUserRepository;
		this.classroomRepository = classroomRepository;
	}

	@GetMapping
	public UserResponse me(Authentication authentication) {
		AppUser user = appUserRepository.findById(Long.valueOf(authentication.getName()))
				.orElseThrow(() -> new InvalidTokenException("존재하지 않는 사용자입니다."));
		ClassroomSummary classroom = user.getClassroomId() == null
				? null
				: classroomRepository.findById(user.getClassroomId())
						.map(found -> new ClassroomSummary(found.getId(), found.getName(), found.getCode()))
						.orElse(null);
		return new UserResponse(user.getId(), user.getName(), user.getRole(), user.getEmail(), classroom);
	}
}
```

- [ ] **Step 5: 전체 테스트 통과 확인**

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --rerun`
Expected: BUILD SUCCESSFUL. `class_code`를 참조하던 코드가 남아 있으면 컴파일 오류로 알려 준다(전부 지운다).

- [ ] **Step 6: 문서 갱신**

`README.md`의 `## 인증 API` 절을 다음 내용에 맞게 고친다.
- 학생 표: `signup`/`login` 요청 본문을 `{classCode, name, phoneLast4}`로, `GET /api/users/me` 응답을 `{id, name, role, email, classroom}`(`classroom`은 `{id, name, code}` 또는 null)로 바꾸고 `PATCH /api/users/me/class-code` 행을 삭제한다.
- 설명 글: "학생은 **참여코드 + 이름 + 휴대폰 번호 뒤 4자리**로 가입·로그인합니다. 같은 이름·같은 번호의 학생도 수업이 다르면 각자 계정입니다." / 시도 제한은 "수업 + 이름 기준"으로 바꾸고 키가 `student:<수업 id>:<이름>`임을 한 줄로 덧붙인다 / "없는 참여코드는 가입에서 400, 로그인에서 일반 401이며 시도 제한을 소모하지 않습니다" 한 줄 추가.
- 오류 메시지 설명의 "로그인 실패 401"은 새 메시지(`참여 코드, 이름 또는 휴대폰 번호가 올바르지 않아요.`)를 가리키게 한다.

`docs/superpowers/specs/2026-09-30-classroom-wordbooks-design.md`의 `## 조각 2 — 학생 로그인 변경 (방향)` 절을 다음 결정에 맞게 고친다(해당 절의 글머리표를 아래 내용으로 교체).
- 요청에 `classCode`가 추가된다. 참여코드는 앞뒤 공백 제거 + 대문자화 후 31자 집합의 6자리가 아니면 "없는 코드"다.
- 학생은 **수업 안에서** `(이름, PIN)`으로 식별한다(`app_user.classroom_id`). 시도 제한 키는 `student:<수업 id>:<정규화한 이름>`이다.
- 없는 코드: 가입은 400 `참여 코드를 확인해 주세요.`, 로그인은 일반 401이며 두 경우 모두 시도 제한 카운터를 만들지 않는다. 코드 공간(약 8.9억)이 넓어 대입이 현실적이지 않지만 코드 조회 자체에는 IP별 제한을 넣지 않았다.
- `class_code` 열과 `PATCH /api/users/me/class-code`는 제거하고, `GET /api/users/me`는 `classroom: {id, name, code}`(없으면 null)를 돌려준다. 수업이 없던 기존 학생 행은 로그인할 수 없지만 `/me`는 깨지지 않는다.
- **`GET /api/classroom/words`는 단어 테이블이 생기는 1b 이후(조각 3)로 미룬다.**
- 앱의 참여 코드 입력 화면은 로그인 폼에 흡수되어 사라진다(앱 저장소 별도 이슈).

- [ ] **Step 7: 커밋**

```bash
git add src/main src/test README.md docs/superpowers/specs/2026-09-30-classroom-wordbooks-design.md
git commit -m "feat: class_code 제거, 내 정보에 소속 수업 추가, 학생 수업 필수 제약 (#13)"
```
