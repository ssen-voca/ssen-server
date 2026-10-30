# 학생 식별을 학교 + 이름 + 뒤 4자리로 변경 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 학생 가입·로그인에서 참여코드를 없애고 학교 + 이름 + 휴대폰 뒤 4자리로 학생을 구분한다. 학교는 자유 입력이며 서버가 정리해 식별 키로 쓴다.

**Architecture:** `app_user`에 `school`/`school_key`를 더하고 학생 식별과 시도 제한 키를 `school_key + name_key`로 바꾼다. 학교 정규화는 이름 정규화(`Names`)와 같은 모양의 `Schools`에 둔다. 학생은 수업에 속하지 않는다(`classroom_id` NULL 허용). 두 태스크로 나눈다: Task 1(정규화 도구, 독립) → Task 2(마이그레이션·엔티티·서비스·DTO·테스트를 한 번에 바꾼다; 중간 상태에서 컴파일이 깨지므로 쪼개지 않는다).

**Tech Stack:** Spring Boot 3.5.9, Java 21, Gradle, PostgreSQL 16, Flyway, Spring Security(stateless), JPA(`ddl-auto: validate`), JUnit 5 + MockMvc(`@SpringBootTest`, 실제 Postgres).

**Spec:** `docs/superpowers/specs/2026-10-30-school-identity-design.md` (이 브랜치에 있다).

## Global Constraints

- 작업 위치: `/Users/JM/dev/ssen/ssen-server`, 브랜치 `feature/15-school-identity`(이슈 #15, `origin/develop` 기준). 코드 들여쓰기는 **탭**.
- 테스트는 항상 `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --rerun`(캐시된 실행은 증거가 아니다). DB 컨테이너(`ssen-db`)가 떠 있어야 한다(꺼져 있으면 `docker compose up -d`; Docker Desktop이 꺼져 있으면 `open -a Docker` 후 기다린다). 8080/8081의 다른 프로세스는 건드리지 않는다.
- 요청 JSON 필드: `school`, `name`, `phoneLast4`. 검증 오류 필드 순서는 `school` → `name` → `phoneLast4`. 메시지: `학교를 입력해 주세요`, `학교 이름은 100자 이하로 입력해 주세요`(기존 이름·번호 메시지는 그대로). 교사 요청은 바꾸지 않는다.
- **학교 정규화**(스펙): `display` = 보이지 않는 문자(`​ ‌ ‍ ⁠ ﻿`) 제거 → NFC → 연속 공백(`(?U)\s+`)을 한 칸으로 → 앞뒤 공백 제거. `key` = 보이지 않는 문자 제거 → NFC → **모든 공백 제거** → `toLowerCase(Locale.ROOT)`; 비었으면 `BLANK`, 100자 초과면(소문자화로 길어질 수 있다) `TOO_LONG`으로 거절하고 이는 제한 카운터·DB에 닿기 전에 일어난다.
- 시도 제한 키: `student:<school_key>:<name_key>`. 선차감(`tryAcquire`)·성공 로그인 시 1회 환불(`release`) 규칙은 그대로다. 교사 키(`teacher-signup`, `teacher:<이메일>`)는 건드리지 않는다.
- 로그인 실패(401): `학교, 이름 또는 휴대폰 번호가 올바르지 않아요.` 후보가 없을 때도 같은 응답이며 BCrypt 비용 1회(더미 해시)를 치른다. 교사 로그인 메시지는 그대로.
- 커밋 메시지에 **AI attribution을 넣지 않는다**(`Co-Authored-By:`, `Generated with`, 로봇 이모지). 이 조직의 `AGENTS.md`와 GitHub Ruleset이 막는다. 커밋 제목은 한국어 `feat: ... (#15)`.
- 범위 밖: 앱 변경(별도 저장소), 학생의 수업 배정, 수업 단어 조회 API, 학교 목록 관리, 시도 제한 강화, 이름 키의 NFC 정규화(`Names`는 그대로).

## Review Focus

1. **같은 학교·이름·번호 중복 가입**은 409, **학교만 다르면** 같은 이름·번호도 각자 가입·로그인되고 한쪽의 잠금(429)이 다른 학교에 번지지 않는다. → Task 2 `StudentSchoolLoginTest`.
2. **학교 표기 변형**(` 동국대 부속고 `, `동국대부속고`, `DONGGUK High`/`donggukhigh`, 자모 분리형 한글 vs 조합형)은 같은 학교다. 빈 값·보이지 않는 문자뿐·100자 초과는 DB/제한 카운터에 닿지 않고 400. → Task 1 `SchoolsTest`, Task 2.
3. **없는 학교로 로그인**: 몇 번이든 일반 401(학교 존재 여부를 드러내지 않음)이고, 키마다 5회 후 429가 된다(키 공간은 요청이 만든다 — 스펙의 알려진 한계). 정상 로그인 1회 환불이 동작한다.
4. **교사와 같은 이름**으로는 학생 로그인이 매칭되지 않는다(역할 조건). → 기존 `TeacherAuthControllerTest.teacherNameDoesNotAffectStudents` 수정판.
5. **마이그레이션**: V7 적용 후 컨텍스트가 `validate`로 뜬다. 학교 없는 학생 행 삽입은 DB가 거절한다. 수업 없는 학생 행(학교 있음)은 저장된다. 옛 학생 행(학교 없음)은 `/api/users/me`가 깨지지 않는다. → `StudentSchemaTest`, `UserControllerTest`.
6. **참여코드 요청 필드가 남아 있지 않다**: `classCode`를 보내도 무시되고(알 수 없는 필드), 학교가 없으면 400.

---

### Task 1: 학교 정규화(`Schools`)와 예외

**Files:**
- Create: `src/main/java/com/ssen/voca/auth/Schools.java`, `src/main/java/com/ssen/voca/auth/InvalidSchoolException.java`
- Modify: `src/main/java/com/ssen/voca/common/GlobalExceptionHandler.java`
- Test: `src/test/java/com/ssen/voca/auth/SchoolsTest.java`

**Interfaces:**
- Produces: `Schools.display(String): String`, `Schools.key(String): String`(둘 다 package-private, 클래스는 `final`), `InvalidSchoolException(String message)` + 상수 `BLANK = "학교를 입력해 주세요"`, `TOO_LONG = "학교 이름은 100자 이하로 입력해 주세요"`, 핸들러는 400 `{"message": ...}`.

- [ ] **Step 1: 실패하는 테스트** — `src/test/java/com/ssen/voca/auth/SchoolsTest.java`

```java
package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

class SchoolsTest {

	@Test
	void displayTrimsCollapsesSpacesAndDropsInvisibleCharacters() {
		assertThat(Schools.display("  동국대   부속\t고 ")).isEqualTo("동국대 부속 고");
		assertThat(Schools.display("서​울﻿고")).isEqualTo("서울고");
	}

	@Test
	void keyIgnoresSpacingAndCase() {
		assertThat(Schools.key(" 동국대 부속고 ")).isEqualTo(Schools.key("동국대부속고"));
		assertThat(Schools.key("DONGGUK High")).isEqualTo("donggukhigh");
		assertThat(Schools.key("Dongguk High")).isEqualTo("donggukhigh");
	}

	@Test
	void keyTreatsDecomposedAndComposedHangulAsTheSame() {
		String composed = "한국고";
		String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
		assertThat(decomposed).isNotEqualTo(composed);
		assertThat(Schools.key(decomposed)).isEqualTo(Schools.key(composed));
		assertThat(Schools.display(decomposed)).isEqualTo(composed);
	}

	@Test
	void blankOrInvisibleOnlyIsRejected() {
		assertThatThrownBy(() -> Schools.key("   ")).isInstanceOf(InvalidSchoolException.class)
				.hasMessage(InvalidSchoolException.BLANK);
		assertThatThrownBy(() -> Schools.key("​⁠")).isInstanceOf(InvalidSchoolException.class)
				.hasMessage(InvalidSchoolException.BLANK);
	}

	@Test
	void tooLongAfterNormalizationIsRejected() {
		assertThat(Schools.key("가".repeat(100))).hasSize(100);
		assertThatThrownBy(() -> Schools.key("가".repeat(101))).isInstanceOf(InvalidSchoolException.class)
				.hasMessage(InvalidSchoolException.TOO_LONG);
		// 'İ'는 소문자화하면 두 글자(i + 결합점)가 되어 100자를 넘을 수 있다.
		assertThatThrownBy(() -> Schools.key("İ".repeat(60))).isInstanceOf(InvalidSchoolException.class)
				.hasMessage(InvalidSchoolException.TOO_LONG);
	}
}
```

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --rerun --tests '*SchoolsTest'` → 컴파일 실패(RED).

- [ ] **Step 2: 구현**

`src/main/java/com/ssen/voca/auth/InvalidSchoolException.java`

```java
package com.ssen.voca.auth;

/** 정규화한 학교가 비었거나 너무 길 때. Bean Validation과 같은 메시지로 400을 내려준다. */
public class InvalidSchoolException extends RuntimeException {

	public static final String BLANK = "학교를 입력해 주세요";
	public static final String TOO_LONG = "학교 이름은 100자 이하로 입력해 주세요";

	public InvalidSchoolException(String message) {
		super(message);
	}
}
```

`src/main/java/com/ssen/voca/auth/Schools.java`

```java
package com.ssen.voca.auth;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** 학교의 표시용·식별용 정규화. 표기 차이(공백·대소문자·한글 조합 방식)를 같은 학교로 본다. */
final class Schools {

	private static final Pattern INVISIBLE = Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]");
	private static final int MAX_LENGTH = 100;

	private Schools() {
	}

	private static String clean(String school) {
		return Normalizer.normalize(INVISIBLE.matcher(school).replaceAll(""), Normalizer.Form.NFC);
	}

	static String display(String school) {
		return clean(school).replaceAll("(?U)\\s+", " ").strip();
	}

	/** 정규화한 학교 키. 모든 공백을 지운다. 비었거나 100자를 넘으면 제한 카운터·DB에 닿기 전에 거절한다. */
	static String key(String school) {
		String key = clean(school).replaceAll("(?U)\\s+", "").toLowerCase(Locale.ROOT);
		if (key.isEmpty()) {
			throw new InvalidSchoolException(InvalidSchoolException.BLANK);
		}
		if (key.length() > MAX_LENGTH) {
			throw new InvalidSchoolException(InvalidSchoolException.TOO_LONG);
		}
		return key;
	}
}
```

`GlobalExceptionHandler.java`: `import com.ssen.voca.auth.InvalidSchoolException;`를 알파벳 순서 위치에 더하고, 기존 `@ExceptionHandler({InvalidNameException.class, InvalidEmailException.class})`를 `@ExceptionHandler({InvalidNameException.class, InvalidSchoolException.class, InvalidEmailException.class})`로 바꾼다(메서드는 그대로).

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --rerun --tests '*SchoolsTest'` → PASS.

- [ ] **Step 3: 커밋**

```bash
git add src
git commit -m "feat: 학교 이름 정규화(Schools)와 학교 검증 예외 추가 (#15)"
```

---

### Task 2: 학생 식별을 학교 기준으로 전환 (마이그레이션·엔티티·서비스·DTO·테스트)

**Files:**
- Create: `src/main/resources/db/migration/V7__student_school.sql`
- Modify: `src/main/java/com/ssen/voca/user/AppUser.java`, `src/main/java/com/ssen/voca/user/AppUserRepository.java`, `src/main/java/com/ssen/voca/user/UserController.java`, `src/main/java/com/ssen/voca/user/dto/UserResponse.java`, `src/main/java/com/ssen/voca/auth/AuthService.java`, `src/main/java/com/ssen/voca/auth/dto/SignupRequest.java`, `src/main/java/com/ssen/voca/auth/dto/LoginRequest.java`, `src/main/java/com/ssen/voca/auth/InvalidCredentialsException.java`, `src/main/java/com/ssen/voca/common/GlobalExceptionHandler.java`
- Delete(사용처가 없어지면): `src/main/java/com/ssen/voca/classroom/ClassroomNotFoundException.java`(+ 핸들러). `ClassCodes`, `ClassroomRepository.findByCode`, `ClassCodeGenerator`, 교사 수업 API는 **그대로 둔다**.
- Test: 아래 "테스트 변경" 참조

**Interfaces:**
- Consumes: `Schools.display/key`, `Names.display/key`, `InvalidSchoolException` (Task 1)
- Produces: `AppUser.getSchool()`, `getSchoolKey()`, 학생 생성자 `AppUser(String name, String nameKey, String secretHash, String school, String schoolKey)`(옛 `classroomId` 학생 생성자와 3인자 생성자는 제거), `AppUserRepository.findAllBySchoolKeyAndNameKeyAndRole(String schoolKey, String nameKey, String role)`(옛 classroomId 쿼리는 제거), `UserResponse(Long id, String name, String role, String email, String school, ClassroomSummary classroom)`

- [ ] **Step 1: 마이그레이션** — `src/main/resources/db/migration/V7__student_school.sql`

```sql
-- 학생 식별을 참여코드(수업) 대신 학교 + 이름 + 뒤 4자리로 바꾼다. 학생은 수업에 속하지 않아도 된다.
ALTER TABLE app_user ADD COLUMN school VARCHAR(100);
ALTER TABLE app_user ADD COLUMN school_key VARCHAR(100);
CREATE INDEX idx_app_user_school_name ON app_user (school_key, name_key);
ALTER TABLE app_user DROP CONSTRAINT ck_app_user_student_classroom;
-- NOT VALID: 학교가 없던 기존(개발용) 학생 행은 검사하지 않고, 새로 쓰는 행부터 검사한다.
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_student_school
    CHECK (role <> 'STUDENT' OR school_key IS NOT NULL) NOT VALID;
```

- [ ] **Step 2: 엔티티·리포지토리**
  - `AppUser`: 필드 `@Column(length = 100) private String school;`, `@Column(name = "school_key", length = 100) private String schoolKey;`를 `classroomId` 위에 더한다. `classroomId` 주석을 `// 학생이 속한 수업 (지금은 모두 null, 배정은 이후 작업. 교사도 null).`로 바꾼다. private 생성자에 `school`, `schoolKey` 인자를 더하고(`classroomId`는 항상 null로 넘긴다), 공개 학생 생성자를 `AppUser(String name, String nameKey, String secretHash, String school, String schoolKey)`로 바꾼다(옛 두 학생 생성자는 지운다). `teacher(...)`는 school/schoolKey를 null로 넘긴다.
  - `AppUserRepository`: `findAllByClassroomIdAndNameKeyAndRole`를 `List<AppUser> findAllBySchoolKeyAndNameKeyAndRole(String schoolKey, String nameKey, String role);`로 바꾼다.

- [ ] **Step 3: DTO** — `SignupRequest`/`LoginRequest`의 첫 필드를 다음으로 바꾼다(두 파일 동일, `classCode` 삭제).

```java
		@NotBlank(message = "학교를 입력해 주세요")
		@Size(max = 100, message = "학교 이름은 100자 이하로 입력해 주세요")
		String school,
```

- [ ] **Step 4: 서비스** — `AuthService.java`에서 `ClassroomRepository`/`ClassCodes`/`Classroom`/`ClassroomNotFoundException` 의존을 모두 없애고 다음으로 바꾼다(이름은 기존 필드·주석 스타일을 따른다).

```java
	@Transactional
	public TokenResponse signup(SignupRequest request) {
		String schoolKey = Schools.key(request.school());
		String nameKey = Names.key(request.name());
		String key = throttleKey(schoolKey, nameKey);
		// 가입 호출은 성공 여부와 무관하게 시도 1회로 센다 (PIN 탐색용으로 쓰일 수 있으므로).
		attemptLimiter.tryAcquire(key);
		// DB 유니크 제약은 해시된 PIN에 걸 수 없어서 같은 학교·같은 이름의 계정을 모두 BCrypt 비교한다.
		// ponytail: 동시에 같은 (학교, 이름, PIN)으로 가입하면 중복이 생길 수 있는 경합은 허용 — 필요하면 advisory lock.
		List<AppUser> sameName = appUserRepository.findAllBySchoolKeyAndNameKeyAndRole(
				schoolKey, nameKey, AppUser.STUDENT);
		if (findMatch(sameName, request.phoneLast4()) != null) {
			throw new StudentAlreadyExistsException();
		}
		AppUser user = new AppUser(
				Names.display(request.name()),
				nameKey,
				passwordEncoder.encode(request.phoneLast4()),
				Schools.display(request.school()),
				schoolKey);
		appUserRepository.save(user);
		return issueTokens(user);
	}

	public TokenResponse login(LoginRequest request) {
		String schoolKey = Schools.key(request.school());
		String nameKey = Names.key(request.name());
		String key = throttleKey(schoolKey, nameKey);
		// 선차감: 병렬 요청도 창당 한도를 넘지 못한다. 실패한 로그인은 차감을 그대로 두고, 성공하면 1회만 돌려준다.
		attemptLimiter.tryAcquire(key);
		List<AppUser> candidates = appUserRepository.findAllBySchoolKeyAndNameKeyAndRole(
				schoolKey, nameKey, AppUser.STUDENT);
		if (candidates.isEmpty()) {
			// 학교·이름이 있는지 드러내지 않도록 같은 401과 비슷한 처리 시간을 쓴다.
			passwordEncoder.matches(request.phoneLast4(), DUMMY_PIN_HASH);
		}
		AppUser user = findMatch(candidates, request.phoneLast4());
		if (user == null) {
			throw new InvalidCredentialsException();
		}
		attemptLimiter.release(key);
		return issueTokens(user);
	}
```

`refresh`, `findMatch`, `issueTokens`, `DUMMY_PIN_HASH`, `THROTTLE_PREFIX`는 그대로. 생성자에서 `ClassroomRepository` 인자와 필드를 지운다. `findClassroom`을 지우고 `throttleKey`를 다음으로 바꾼다.

```java
	private static String throttleKey(String schoolKey, String nameKey) {
		return THROTTLE_PREFIX + schoolKey + ":" + nameKey;
	}
```
더미 해시 주석의 "unknown class code / name"을 "unknown school / name"으로 고친다.

`InvalidCredentialsException`의 기본 메시지를 `학교, 이름 또는 휴대폰 번호가 올바르지 않아요.`로 바꾼다.

`ClassroomNotFoundException`이 `src/main` 어디에서도 쓰이지 않으면(`grep -rn ClassroomNotFoundException src`) 클래스와 `GlobalExceptionHandler`의 핸들러·import를 지운다. 쓰는 곳이 남아 있으면 그대로 둔다.

- [ ] **Step 5: 내 정보** — `UserResponse`를 `(Long id, String name, String role, String email, String school, ClassroomSummary classroom)`로 바꾸고, `UserController.me`의 반환을 `new UserResponse(user.getId(), user.getName(), user.getRole(), user.getEmail(), user.getSchool(), classroom)`로 바꾼다(교사·옛 학생 행은 `school`이 null).

- [ ] **Step 6: 테스트 변경** — 기존 패턴(`@SpringBootTest`, `@AutoConfigureMockMvc`, `@Transactional`, `ObjectMapper`로 본문 작성, 테스트마다 **서로 다른 학교 이름**을 써서 시도 제한 키가 겹치지 않게 한다)을 따른다. 새 학생 행을 직접 만드는 곳은 새 생성자를 쓴다.
  1. `src/test/java/com/ssen/voca/auth/StudentClassroomLoginTest.java` → `StudentSchoolLoginTest.java`로 이름을 바꾸고 다음 시나리오를 검증하도록 다시 쓴다(수업 픽스처는 쓰지 않는다).
     - 같은 학교·이름·번호로 두 번 가입 → 두 번째는 409. 같은 학교·이름·**다른 번호**는 둘 다 가입되고 각자 번호로 로그인된다(상대 번호로는 401).
     - **학교만 다른** 같은 이름·번호는 둘 다 가입되고 각자 로그인된다. 한 학교의 키가 5회 실패로 429가 돼도 다른 학교의 같은 이름 로그인은 영향받지 않는다.
     - 학교 표기 변형(`" 동국대 부속고 "` 가입 → `"동국대부속고"`로 로그인 성공, 같은 변형으로 재가입은 409, `"Seoul High"`/`"seoulhigh"`)이 같은 학교로 취급된다.
     - 없는 학교로 로그인 → 401 `학교, 이름 또는 휴대폰 번호가 올바르지 않아요.`(가입된 학교의 틀린 번호와 같은 본문). 5회 후 같은 키는 429.
     - 성공 로그인 1회는 환불된다(기존 `release` 시나리오를 학교 키로 옮긴다).
     - 빈 학교·공백뿐·`​`뿐 → 400 `학교를 입력해 주세요`(가입과 로그인 모두, 제한 카운터를 쓰지 않는다: 같은 요청을 6번 보내도 429가 아니다). 101자 → 400 `학교 이름은 100자 이하로 입력해 주세요`. 필드 검증 순서는 학교 → 이름 → 번호(셋 다 비면 첫 메시지는 학교).
     - `classCode`를 같이 보내도 무시된다(학교가 있으면 가입된다).
     - 가입 응답은 토큰 두 개이고, `/api/users/me`는 `school`에 표시용 학교(정리된 공백)를 담고 `classroom`은 null이다.
  2. `AuthControllerTest`, `AuthServiceTest`, `AuthThrottleTest`, `AuthConcurrencyTest`, `CorsTest`, `RoleAuthorizationTest`, `TeacherAuthControllerTest`(특히 `teacherNameDoesNotAffectStudents`: 교사와 같은 이름·같은 학교로 학생 로그인이 교사 행에 매칭되지 않음), `UserControllerTest`, `TeacherClassroomControllerTest`, `ClassroomServiceTest` 등에서 `classCode`/`ClassroomFixture`를 쓰는 곳을 학교 방식으로 바꾼다. 교사 수업 API 테스트에서 수업·참여코드 자체를 검증하는 부분은 **그대로** 둔다(교사 기능은 안 바뀐다). 컴파일되고 통과하도록만 최소한으로 고친다. `ClassroomFixture`는 교사 수업 테스트가 계속 쓰면 유지, 아무도 안 쓰면 삭제한다.
  3. `src/test/java/com/ssen/voca/user/StudentClassroomRepositoryTest.java` → `StudentSchoolRepositoryTest.java`: `findAllBySchoolKeyAndNameKeyAndRole`가 학교·이름 키와 역할로만 찾는다(다른 학교·다른 이름·교사 행은 제외).
  4. `src/test/java/com/ssen/voca/user/StudentSchemaTest.java`: 학교 없는 학생 INSERT는 `DataIntegrityViolationException`; 학교가 있고 수업이 없는 학생은 저장되고 `getClassroomId()`가 null; 교사는 학교·수업 없이 저장된다.
  5. `UserControllerTest`: 학생은 `school` 표시값과 `classroom: null`, 교사는 `school: null`, **학교가 없는 옛 학생 행**(JdbcTemplate으로 `school_key` 없이 INSERT는 CHECK가 막으므로, `ALTER`가 아니라 `school_key`가 있는 행을 만든 뒤 `UPDATE app_user SET school=NULL, school_key=NULL`로 만든다. `NOT VALID` 제약은 UPDATE에는 다시 적용되므로 이 방법이 안 되면 이 케이스는 보고서에 "DB 제약상 만들 수 없음"으로 적고 건너뛴다)에서도 500이 아님을 확인한다.

- [ ] **Step 7: 전체 검증**

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --rerun`
Expected: 모든 테스트 통과, 출력에 경고·스택트레이스 없음. `grep -rn "classCode\|ClassCodes" src/main/java/com/ssen/voca/auth src/main/java/com/ssen/voca/user` 결과가 없어야 한다(교사 수업 패키지의 참여코드는 남는다).
수동 확인(선택): 앱을 띄우지 않고 서버만 `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew bootRun`으로 띄워 `curl`로 가입 → 로그인 → `/api/users/me`를 한 번 훑는다(끝나면 서버 프로세스를 정확히 그것만 종료한다).

- [ ] **Step 8: 커밋**

```bash
git add -A src
git commit -m "feat: 학생 가입·로그인을 학교 + 이름 + 뒤 4자리로 변경하고 참여코드 입력 제거 (#15)"
```

---

### Task 3: 문서 갱신

**Files:**
- Modify: `README.md`, `docs/superpowers/specs/2026-09-30-classroom-wordbooks-design.md`, `docs/superpowers/specs/2026-10-30-school-identity-design.md`(문장 다듬기)

- [ ] **Step 1:** `README.md`의 학생 가입·로그인 설명과 요청 예시를 `school` 필드로 바꾸고 참여코드 입력을 학생 흐름에서 뺀다(교사의 수업 생성·참여코드 발급 설명은 남긴다). `grep -n "classCode\|참여" README.md`로 찾는다.
- [ ] **Step 2:** `2026-09-30-classroom-wordbooks-design.md`의 "조각 2 — 학생 로그인 변경" 섹션 첫머리에 한 단락을 더한다: "**변경(2026-10-30, #15):** 학생 식별은 참여코드가 아니라 학교 + 이름 + 뒤 4자리로 바뀌었다. 학생은 가입 시 수업에 속하지 않으며 수업 배정은 이후 작업에서 정한다. 자세한 내용은 `2026-10-30-school-identity-design.md`." 본문의 기존 서술은 지우지 않는다.
- [ ] **Step 3:** 신규 스펙의 "알려진 한계" 첫 항목 중 `계정이 학교·이름 전체에 걸쳐 많아졌다.` 문장을 `학교와 이름만 알면 시도할 수 있는 대상이 수업 단위보다 넓어졌다.`로 바꾼다.
- [ ] **Step 4:** 커밋 `git add -A README.md docs && git commit -m "docs: 학교 기반 학생 식별에 맞춰 README와 설계 문서 갱신 (#15)"`
