# 교사 계정 · 수업 생성 API (조각 1a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 교사가 가입 코드로 교사 계정을 만들고 로그인해서 수업을 만들면 6자리 참여코드가 발급되는 서버 API를 추가한다.

**Architecture:** `app_user` 한 테이블에 `role`(STUDENT/TEACHER)을 두고 교사는 이메일 + 비밀번호, 학생은 이름 + 뒤 4자리를 `secret_hash`에 담는다. 액세스 토큰에 `role` claim을 넣고 필터가 권한(`ROLE_*`)을 만들어 `/api/teacher/**`를 교사에게만 연다. 시도 제한은 기존 `LoginAttemptLimiter`(선차감·성공 시 1회 환불)를 키만 달리해 재사용한다. 수업(`classroom`)은 교사가 만들고 서버가 참여코드를 발급한다.

**Tech Stack:** Spring Boot 3.5.9, Java 21, Gradle, PostgreSQL 16, Flyway, Spring Security(stateless), jjwt, BCrypt, JPA(`ddl-auto: validate`), JUnit 5 + MockMvc(`@SpringBootTest`, 실제 Postgres).

**Spec:** `docs/superpowers/specs/2026-09-30-classroom-wordbooks-design.md` (조각 1a). 단어 업로드(1b: `classroom_word`, CSV)는 교사 회의 후 확정이므로 **이 계획에 포함하지 않는다.**

## Global Constraints

- 작업 위치: `/Users/JM/dev/ssen/ssen-server`, 브랜치 `feature/11-teacher-classroom`(이슈 #11). 코드 들여쓰기는 **탭**(기존 파일과 동일).
- 테스트 실행 전에 DB가 떠 있어야 한다: `docker compose up -d`. 명령은 항상 `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test ...` 형태다(호스트 5433, 도커 기본 비밀번호 `ssen`). 8080/8081에서 도는 다른 프로세스는 건드리지 않는다.
- 앱이 직접 내는 오류 본문은 `{"message": "<한국어>"}` 형태다. 검증 실패는 첫 번째 오류 메시지를 담는다(필드 순서는 `GlobalExceptionHandler.FIELD_ORDER`).
- 이메일은 **소문자·앞뒤 공백 제거**로 저장·비교한다. 교사 비밀번호는 8자 이상이고 **UTF-8 72바이트 이하**(BCrypt 한도)다.
- 교사 가입 코드: 환경변수 `TEACHER_INVITE_CODE`(운영 필수, local 프로필 기본값 `local-teacher-code`). 상수 시간 비교한다.
- 교사 가입 코드 시도 제한 키는 `teacher-signup` 하나(5회/5분 공유), 교사 로그인 키는 `teacher:<소문자 이메일>`. 둘 다 `tryAcquire`로 선차감하고 성공하면 `release`로 1회만 돌려준다.
- 참여코드: `ABCDEFGHJKMNPQRSTUVWXYZ23456789`(0/O, 1/I/L 제외) 6자리, `SecureRandom`.
- **교사는 자기 수업만** 본다. 학생 로그인·가입은 `role = 'STUDENT'` 계정만 대상으로 한다.
- 커밋 메시지에 **AI attribution을 넣지 않는다**(`Co-Authored-By:`, `Generated with`, 로봇 이모지 모두). 이 조직의 `AGENTS.md`와 GitHub Ruleset이 막는다. 커밋 제목은 한국어 `feat: ... (#11)`.
- 범위 밖: 단어 업로드(`classroom_word`, CSV), 학생 로그인에 참여코드 추가(조각 2), 앱 변경, 수업 수정·삭제.

## Review Focus

1. **교사와 같은 이름의 학생**: 학생 가입·로그인이 교사 행과 섞이지 않는다(교사 행은 학생 PIN 대조 대상이 아니다). → Task 1 `TeacherSchemaTest`, Task 3 `TeacherAuthControllerTest.teacherNameDoesNotAffectStudents`.
2. **72바이트를 넘는 비밀번호**(한글 25자 = 75바이트): 조용히 잘리지 않고 가입 400, 로그인은 일반 401. → Task 3 `TeacherAuthControllerTest`.
3. **가입 코드 대입**: 5번 틀리면 6번째는 맞는 코드여도 429이고, 정상 가입은 한도를 소모하지 않는다. → Task 3 `TeacherThrottleTest`.
4. **role claim이 없는 옛 액세스 토큰**: 교사 권한을 얻지 못한다(학생 취급). → Task 2 `RoleAuthorizationTest.tokenWithoutRoleClaimIsTreatedAsStudent`.
5. **이메일 변형**(대문자, 앞뒤 공백): 가입·로그인에서 같은 계정으로 취급되고 중복 가입은 409. → Task 3 `TeacherAuthControllerTest`.
6. (Task 4) 전각 공백뿐인 수업 이름은 400이다. → `TeacherClassroomControllerTest`.

---

### Task 1: 스키마와 엔티티 — 교사 컬럼, 수업 테이블, 역할별 학생 조회

**Files:**
- Create: `src/main/resources/db/migration/V4__teacher_classroom.sql`
- Modify: `src/main/java/com/ssen/voca/user/AppUser.java`, `src/main/java/com/ssen/voca/user/AppUserRepository.java`, `src/main/java/com/ssen/voca/auth/AuthService.java`
- Modify: `src/test/java/com/ssen/voca/user/AppUserRepositoryTest.java`
- Test: `src/test/java/com/ssen/voca/user/TeacherSchemaTest.java`

**Interfaces:**
- Produces: `AppUser.STUDENT`, `AppUser.TEACHER`(문자열 상수), `AppUser.teacher(String name, String nameKey, String email, String secretHash)`, `getSecretHash()`(기존 `getPinHash()` 대체), `getEmail()`; `AppUserRepository.findAllByNameKeyAndRole(String, String)`(기존 `findAllByNameKey` 대체), `findByEmail(String): Optional<AppUser>`, `existsByEmail(String): boolean`. `classroom` 테이블(Task 4가 사용).

- [ ] **Step 1: 실패하는 테스트 작성** — `src/test/java/com/ssen/voca/user/TeacherSchemaTest.java`

```java
package com.ssen.voca.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class TeacherSchemaTest {

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void teacherIsSavedWithEmailAndRole() {
		appUserRepository.saveAndFlush(AppUser.teacher("스키마교사", "스키마교사", "schema-teacher@example.com", "hash"));

		AppUser found = appUserRepository.findByEmail("schema-teacher@example.com").orElseThrow();
		assertThat(found.getRole()).isEqualTo(AppUser.TEACHER);
		assertThat(found.getEmail()).isEqualTo("schema-teacher@example.com");
		assertThat(appUserRepository.existsByEmail("schema-teacher@example.com")).isTrue();
		assertThat(appUserRepository.existsByEmail("nobody@example.com")).isFalse();
	}

	@Test
	void teacherRowWithoutEmailViolatesCheckConstraint() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO app_user (name, name_key, secret_hash, role) VALUES ('교사', '교사', 'h', 'TEACHER')"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void duplicateEmailViolatesUniqueIndex() {
		appUserRepository.saveAndFlush(AppUser.teacher("중복가", "중복가", "dup-schema@example.com", "h"));

		assertThatThrownBy(() -> appUserRepository.saveAndFlush(
				AppUser.teacher("중복나", "중복나", "dup-schema@example.com", "h")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void studentsWithoutEmailCanCoexist() {
		appUserRepository.saveAndFlush(new AppUser("무이메일가", "스키마무이메일", "h"));
		appUserRepository.saveAndFlush(new AppUser("무이메일나", "스키마무이메일", "h"));

		assertThat(appUserRepository.findAllByNameKeyAndRole("스키마무이메일", AppUser.STUDENT)).hasSize(2);
	}

	@Test
	void roleFilterSeparatesTeacherAndStudentWithSameNameKey() {
		appUserRepository.saveAndFlush(AppUser.teacher("스키마동명", "스키마동명", "same-name@example.com", "h"));
		appUserRepository.saveAndFlush(new AppUser("스키마동명", "스키마동명", "h"));

		assertThat(appUserRepository.findAllByNameKeyAndRole("스키마동명", AppUser.STUDENT))
				.extracting(AppUser::getRole).containsExactly(AppUser.STUDENT);
		assertThat(appUserRepository.findAllByNameKeyAndRole("스키마동명", AppUser.TEACHER))
				.extracting(AppUser::getRole).containsExactly(AppUser.TEACHER);
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `docker compose up -d && DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --tests '*TeacherSchemaTest'`
Expected: 컴파일 FAIL (`AppUser.teacher`, `AppUser.TEACHER`, `findByEmail`, `findAllByNameKeyAndRole` 없음).

- [ ] **Step 3: 마이그레이션 작성** — `src/main/resources/db/migration/V4__teacher_classroom.sql`

```sql
-- 교사 계정과 수업(조각 1a). 비밀번호(교사)와 PIN(학생)을 같은 열에 담으므로 이름을 일반화한다.
ALTER TABLE app_user RENAME COLUMN pin_hash TO secret_hash;
ALTER TABLE app_user ADD COLUMN email VARCHAR(255);
CREATE UNIQUE INDEX uq_app_user_email ON app_user (email) WHERE email IS NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_teacher_email CHECK (role <> 'TEACHER' OR email IS NOT NULL);

CREATE TABLE classroom (
    id         BIGSERIAL PRIMARY KEY,
    teacher_id BIGINT       NOT NULL REFERENCES app_user (id),
    name       VARCHAR(100) NOT NULL,
    code       VARCHAR(6)   NOT NULL UNIQUE,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_classroom_teacher ON classroom (teacher_id);
```

- [ ] **Step 4: 엔티티와 저장소 수정**

`src/main/java/com/ssen/voca/user/AppUser.java` 전체를 다음으로 교체한다(import는 기존과 같다).

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

	@Column(name = "class_code", length = 50)
	private String classCode;

	@Column(nullable = false, length = 20)
	private String role;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public AppUser(String name, String nameKey, String secretHash) {
		this.name = name;
		this.nameKey = nameKey;
		this.secretHash = secretHash;
		this.role = STUDENT;
		this.createdAt = LocalDateTime.now();
	}

	public static AppUser teacher(String name, String nameKey, String email, String secretHash) {
		AppUser user = new AppUser(name, nameKey, secretHash);
		user.email = email;
		user.role = TEACHER;
		return user;
	}

	public void updateClassCode(String classCode) {
		this.classCode = classCode;
	}
}
```

`src/main/java/com/ssen/voca/user/AppUserRepository.java` 전체를 다음으로 교체한다.

```java
package com.ssen.voca.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	List<AppUser> findAllByNameKeyAndRole(String nameKey, String role);

	Optional<AppUser> findByEmail(String email);

	boolean existsByEmail(String email);
}
```

- [ ] **Step 5: 기존 호출부 수정**

`src/main/java/com/ssen/voca/auth/AuthService.java`에서 세 곳을 바꾼다.

- `appUserRepository.findAllByNameKey(nameKey)` 두 곳(가입의 `findMatch(...)` 안, 로그인의 `candidates` 대입) → `appUserRepository.findAllByNameKeyAndRole(nameKey, AppUser.STUDENT)`
- `findMatch` 안의 `candidate.getPinHash()` → `candidate.getSecretHash()`

`src/test/java/com/ssen/voca/user/AppUserRepositoryTest.java`에서:
- `appUserRepository.findAllByNameKey("repo-김학생")` → `appUserRepository.findAllByNameKeyAndRole("repo-김학생", AppUser.STUDENT)`
- `appUserRepository.findAllByNameKey("repo-nobody")` → `appUserRepository.findAllByNameKeyAndRole("repo-nobody", AppUser.STUDENT)`
- `AppUser::getPinHash` → `AppUser::getSecretHash`

- [ ] **Step 6: 전체 테스트 통과 확인**

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test`
Expected: BUILD SUCCESSFUL, 기존 65개 + `TeacherSchemaTest` 5개 모두 통과(Hibernate `validate`가 새 컬럼 매핑을 통과해야 한다).

- [ ] **Step 7: 커밋**

```bash
git add src/main/resources/db/migration/V4__teacher_classroom.sql src/main/java/com/ssen/voca/user src/main/java/com/ssen/voca/auth/AuthService.java src/test/java/com/ssen/voca/user
git commit -m "feat: 교사 계정·수업 테이블과 역할별 학생 조회 추가 (#11)"
```

---

### Task 2: 액세스 토큰의 role과 `/api/teacher/**` 권한

**Files:**
- Modify: `src/main/java/com/ssen/voca/auth/JwtService.java`, `src/main/java/com/ssen/voca/auth/JwtAuthenticationFilter.java`, `src/main/java/com/ssen/voca/auth/SecurityConfig.java`, `src/main/java/com/ssen/voca/auth/AuthService.java`
- Modify: `src/test/java/com/ssen/voca/auth/JwtServiceTest.java`
- Test: `src/test/java/com/ssen/voca/auth/RoleAuthorizationTest.java`

**Interfaces:**
- Consumes: `AppUser.TEACHER`, `AppUser.teacher(...)`, `AppUserRepository` (Task 1)
- Produces: `JwtService.generateAccessToken(Long userId, String name, String role)`(기존 2인자 대체), `JwtService.ROLE_CLAIM = "role"`. 필터가 만드는 권한은 `ROLE_STUDENT`/`ROLE_TEACHER`. `/api/teacher/signup`, `/api/teacher/login`은 공개이고 그 외 `/api/teacher/**`는 `TEACHER`만 통과한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/ssen/voca/auth/RoleAuthorizationTest.java`

```java
package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

// /api/teacher/anything 에는 핸들러가 없다. 권한을 통과하면 404, 못 통과하면 401/403이므로 권한만 따로 검증할 수 있다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RoleAuthorizationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Value("${app.jwt.secret}")
	private String secret;

	private ResultActions getTeacherPath(String token) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/teacher/anything");
		if (token != null) {
			request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		return mockMvc.perform(request);
	}

	/** role claim이 없던 시절의 액세스 토큰. */
	private String legacyToken() {
		Date now = new Date();
		return Jwts.builder()
				.subject("1")
				.claim("name", "옛토큰")
				.issuedAt(now)
				.expiration(new Date(now.getTime() + 60_000))
				.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
				.compact();
	}

	@Test
	void noTokenIsUnauthorized() throws Exception {
		getTeacherPath(null).andExpect(status().isUnauthorized());
	}

	@Test
	void studentTokenIsForbidden() throws Exception {
		getTeacherPath(jwtService.generateAccessToken(1L, "학생", AppUser.STUDENT))
				.andExpect(status().isForbidden());
	}

	@Test
	void teacherTokenPassesAuthorization() throws Exception {
		getTeacherPath(jwtService.generateAccessToken(1L, "교사", AppUser.TEACHER))
				.andExpect(status().isNotFound());
	}

	@Test
	void tokenWithoutRoleClaimIsTreatedAsStudent() throws Exception {
		getTeacherPath(legacyToken()).andExpect(status().isForbidden());
	}

	@Test
	void refreshKeepsTeacherRole() throws Exception {
		AppUser teacher = appUserRepository.save(
				AppUser.teacher("교사리프", "교사리프", "refresh-teacher@example.com", "hash"));
		String refreshToken = jwtService.generateRefreshToken(teacher.getId());

		String body = mockMvc.perform(post("/api/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String accessToken = objectMapper.readTree(body).get("accessToken").asText();

		assertThat(jwtService.parseAccessToken(accessToken).get(JwtService.ROLE_CLAIM, String.class))
				.isEqualTo(AppUser.TEACHER);
		getTeacherPath(accessToken).andExpect(status().isNotFound());
	}
}
```

`src/test/java/com/ssen/voca/auth/JwtServiceTest.java`의 기존 두 호출 `jwtService.generateAccessToken(1L, "김학생")`을 `jwtService.generateAccessToken(1L, "김학생", "STUDENT")`로 바꾸고, 같은 클래스에 테스트를 하나 더한다(`jwtService` 필드를 그대로 쓴다).

```java
	@Test
	void accessTokenCarriesRoleClaim() {
		String token = jwtService.generateAccessToken(1L, "김교사", "TEACHER");

		assertThat(jwtService.parseAccessToken(token).get("role", String.class)).isEqualTo("TEACHER");
	}
```

- [ ] **Step 2: 실패 확인**

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --tests '*RoleAuthorizationTest' --tests '*JwtServiceTest'`
Expected: 컴파일 FAIL (3인자 `generateAccessToken`, `JwtService.ROLE_CLAIM` 없음).

- [ ] **Step 3: 구현**

`JwtService.java` — 상수와 메서드를 바꾼다.

```java
	public static final String ROLE_CLAIM = "role";
```
(클래스 상단 `TYPE_CLAIM` 선언 위에 추가)

```java
	public String generateAccessToken(Long userId, String name, String role) {
		Date now = new Date();
		return Jwts.builder()
				.subject(String.valueOf(userId))
				.claim("name", name)
				.claim(ROLE_CLAIM, role)
				.issuedAt(now)
				.expiration(new Date(now.getTime() + accessTokenTtlMillis))
				.signWith(key)
				.compact();
	}
```

`AuthService.java` — `generateAccessToken(...)` 호출 두 곳(`refresh`, `issueTokens`)에 role 인자를 더한다.

```java
		return new AccessTokenResponse(jwtService.generateAccessToken(user.getId(), user.getName(), user.getRole()));
```
```java
		return new TokenResponse(
				jwtService.generateAccessToken(user.getId(), user.getName(), user.getRole()),
				jwtService.generateRefreshToken(user.getId()));
```

`JwtAuthenticationFilter.java` — try 블록을 다음으로 바꾸고 import `io.jsonwebtoken.Claims`, `org.springframework.security.core.authority.SimpleGrantedAuthority`를 추가한다.

```java
			try {
				Claims claims = jwtService.parseAccessToken(header.substring(7));
				// role claim이 없는 옛 토큰은 가장 낮은 권한(학생)으로 본다.
				String role = claims.get(JwtService.ROLE_CLAIM, String.class);
				if (role == null) {
					role = "STUDENT";
				}
				SecurityContextHolder.getContext().setAuthentication(
						new UsernamePasswordAuthenticationToken(
								claims.getSubject(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
			} catch (InvalidTokenException ignored) {
				SecurityContextHolder.clearContext();
			}
```

`SecurityConfig.java` — `authorizeHttpRequests` 블록을 다음으로 바꾼다.

```java
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/health", "/api/auth/**", "/api/teacher/signup", "/api/teacher/login",
								"/error").permitAll()
						.requestMatchers("/api/teacher/**").hasRole("TEACHER")
						.anyRequest().authenticated())
```

- [ ] **Step 4: 전체 테스트 통과 확인**

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test`
Expected: BUILD SUCCESSFUL (다른 테스트가 2인자 `generateAccessToken`을 쓰고 있었다면 컴파일 오류가 나므로 3인자로 고친다).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/ssen/voca/auth src/test/java/com/ssen/voca/auth
git commit -m "feat: 액세스 토큰에 role을 담고 교사 API 권한을 적용 (#11)"
```

---

### Task 3: 교사 가입(가입 코드) · 로그인

**Files:**
- Create: `src/main/java/com/ssen/voca/auth/Names.java`, `src/main/java/com/ssen/voca/auth/TeacherAuthService.java`, `src/main/java/com/ssen/voca/auth/TeacherAuthController.java`
- Create: `src/main/java/com/ssen/voca/auth/dto/TeacherSignupRequest.java`, `src/main/java/com/ssen/voca/auth/dto/TeacherLoginRequest.java`
- Create: `src/main/java/com/ssen/voca/auth/InvalidInviteCodeException.java`, `TeacherEmailExistsException.java`, `InvalidPasswordException.java`
- Modify: `src/main/java/com/ssen/voca/auth/AuthService.java`, `src/main/java/com/ssen/voca/auth/InvalidCredentialsException.java`, `src/main/java/com/ssen/voca/common/GlobalExceptionHandler.java`, `src/main/resources/application.yml`
- Test: `src/test/java/com/ssen/voca/auth/TeacherAuthControllerTest.java`, `src/test/java/com/ssen/voca/auth/TeacherThrottleTest.java`

**Interfaces:**
- Consumes: `AppUser.teacher(...)`, `findByEmail`, `existsByEmail` (Task 1), 3인자 `generateAccessToken` (Task 2), `LoginAttemptLimiter.tryAcquire/release`, `MutableClock`(테스트용, 이미 있음)
- Produces: `TeacherAuthService.signup(TeacherSignupRequest): TokenResponse`, `login(TeacherLoginRequest): TokenResponse`; `POST /api/teacher/signup`(201), `POST /api/teacher/login`(200); 이름 정규화 `Names.display(String)`, `Names.key(String)`(패키지 전용); `InvalidCredentialsException(String message)`.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/ssen/voca/auth/TeacherAuthControllerTest.java`

```java
package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

// 가입 코드 시도 제한은 공유 싱글턴이다. 잘못된 코드로 가입을 시도하는 테스트는 이 클래스에서 1번만 쓰고,
// 한도 자체는 TeacherThrottleTest(전용 시계)에서 검증한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TeacherAuthControllerTest {

	private static final String BAD_INVITE = "교사 가입 코드가 올바르지 않아요.";
	private static final String EMAIL_EXISTS = "이미 가입된 이메일이에요. 로그인해 주세요.";
	private static final String BAD_CREDENTIALS = "이메일 또는 비밀번호가 올바르지 않아요.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JwtService jwtService;

	@Value("${app.teacher.invite-code}")
	private String inviteCode;

	private ResultActions postJson(String path, Map<String, String> body) throws Exception {
		return mockMvc.perform(MockMvcRequestBuilders.post(path)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)));
	}

	private ResultActions signup(String name, String email, String password, String code) throws Exception {
		return postJson("/api/teacher/signup",
				Map.of("name", name, "email", email, "password", password, "inviteCode", code));
	}

	private ResultActions login(String email, String password) throws Exception {
		return postJson("/api/teacher/login", Map.of("email", email, "password", password));
	}

	@Test
	void signupReturns201WithTokensAndTeacherRole() throws Exception {
		String body = signup("가입교사", "ctl-teacher@example.com", "password1", inviteCode)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andReturn().getResponse().getContentAsString();

		String accessToken = objectMapper.readTree(body).get("accessToken").asText();
		assertThat(jwtService.parseAccessToken(accessToken).get(JwtService.ROLE_CLAIM, String.class))
				.isEqualTo("TEACHER");
	}

	@Test
	void signupWithWrongInviteCodeReturns403() throws Exception {
		signup("틀린코드교사", "wrong-code@example.com", "password1", "not-the-code")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(BAD_INVITE));
	}

	@Test
	void duplicateEmailReturns409IgnoringCaseAndPadding() throws Exception {
		signup("중복교사", "Dup.Teacher@Example.com", "password1", inviteCode).andExpect(status().isCreated());

		signup("중복교사둘", " dup.teacher@example.com ", "password1", inviteCode)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(EMAIL_EXISTS));
	}

	@Test
	void validationErrorsReturn400WithMessage() throws Exception {
		signup("　", "v1@example.com", "password1", inviteCode)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("이름을 입력해 주세요"));
		signup("검증교사", "not-an-email", "password1", inviteCode)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("올바른 이메일 형식이 아니에요"));
		signup("검증교사", "v3@example.com", "short", inviteCode)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("비밀번호는 8자 이상으로 입력해 주세요"));
		signup("검증교사", "v4@example.com", "password1", "")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("교사 가입 코드를 입력해 주세요"));
	}

	@Test
	void passwordOver72BytesIsRejectedNotTruncated() throws Exception {
		String koreanPassword = "가".repeat(25); // 75바이트

		signup("긴비번교사", "long-pw@example.com", koreanPassword, inviteCode)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("비밀번호가 너무 길어요. 한글 24자, 영문·숫자 72자 이내로 입력해 주세요."));
		login("long-pw@example.com", koreanPassword)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(BAD_CREDENTIALS));
	}

	@Test
	void loginSucceedsIgnoringEmailCaseAndPadding() throws Exception {
		signup("로그인교사", "Login.Teacher@Example.com", "password1", inviteCode).andExpect(status().isCreated());

		login(" login.teacher@EXAMPLE.com ", "password1")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty());
	}

	@Test
	void wrongPasswordAndUnknownEmailGetTheSame401() throws Exception {
		signup("실패교사", "fail-teacher@example.com", "password1", inviteCode).andExpect(status().isCreated());

		login("fail-teacher@example.com", "wrong-password")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(BAD_CREDENTIALS));
		login("nobody-teacher@example.com", "password1")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(BAD_CREDENTIALS));
	}

	@Test
	void teacherNameDoesNotAffectStudents() throws Exception {
		signup("동명교사", "same-name-teacher@example.com", "password1", inviteCode).andExpect(status().isCreated());

		// 같은 이름의 학생이 같은 이름 키로 가입·로그인해도 교사 행과 섞이지 않는다.
		postJson("/api/auth/signup", Map.of("name", "동명교사", "phoneLast4", "1234"))
				.andExpect(status().isCreated());
		postJson("/api/auth/login", Map.of("name", "동명교사", "phoneLast4", "1234"))
				.andExpect(status().isOk());
		postJson("/api/auth/login", Map.of("name", "동명교사", "phoneLast4", "9999"))
				.andExpect(status().isUnauthorized());
	}
}
```

`src/test/java/com/ssen/voca/auth/TeacherThrottleTest.java`

```java
package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssen.voca.auth.dto.TeacherLoginRequest;
import com.ssen.voca.auth.dto.TeacherSignupRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

// 전용 시계(별도 Spring 컨텍스트)를 쓰므로 다른 테스트의 시도 횟수와 섞이지 않는다.
@SpringBootTest
@Import(TeacherThrottleTest.ClockConfig.class)
@Transactional
class TeacherThrottleTest {

	@TestConfiguration
	static class ClockConfig {
		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock();
		}
	}

	@Autowired
	private TeacherAuthService teacherAuthService;

	@Autowired
	private MutableClock clock;

	@Value("${app.teacher.invite-code}")
	private String inviteCode;

	@BeforeEach
	void openFreshWindow() {
		clock.advanceMillis(6 * 60 * 1000);
	}

	private void signupWithCode(String email, String code) {
		teacherAuthService.signup(new TeacherSignupRequest("제한교사", email, "password1", code));
	}

	@Test
	void sixthSignupAttemptIsRejectedEvenWithCorrectCode() {
		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> signupWithCode("throttle-a@example.com", "wrong-code"))
					.isInstanceOf(InvalidInviteCodeException.class);
		}

		assertThatThrownBy(() -> signupWithCode("throttle-a@example.com", inviteCode))
				.isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void successfulSignupsDoNotConsumeTheBudget() {
		for (int i = 0; i < 8; i++) {
			int n = i;
			assertThatCode(() -> signupWithCode("throttle-ok-" + n + "@example.com", inviteCode))
					.doesNotThrowAnyException();
		}
	}

	@Test
	void signupBudgetResetsAfterTheWindow() {
		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> signupWithCode("throttle-b@example.com", "wrong-code"))
					.isInstanceOf(InvalidInviteCodeException.class);
		}
		clock.advanceMillis(5 * 60 * 1000);

		assertThatCode(() -> signupWithCode("throttle-b@example.com", inviteCode)).doesNotThrowAnyException();
	}

	@Test
	void sixthFailedLoginIsRejectedEvenWithCorrectPassword() {
		signupWithCode("throttle-login@example.com", inviteCode);
		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> teacherAuthService.login(
					new TeacherLoginRequest("throttle-login@example.com", "wrong-password")))
					.isInstanceOf(InvalidCredentialsException.class);
		}

		assertThatThrownBy(() -> teacherAuthService.login(
				new TeacherLoginRequest("throttle-login@example.com", "password1")))
				.isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void otherEmailIsUnaffectedByAnotherEmailsFailures() {
		signupWithCode("throttle-c@example.com", inviteCode);
		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> teacherAuthService.login(
					new TeacherLoginRequest("throttle-d@example.com", "wrong-password")))
					.isInstanceOf(InvalidCredentialsException.class);
		}

		assertThatCode(() -> teacherAuthService.login(new TeacherLoginRequest("throttle-c@example.com", "password1")))
				.doesNotThrowAnyException();
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --tests '*TeacherAuthControllerTest' --tests '*TeacherThrottleTest'`
Expected: 컴파일 FAIL (`TeacherAuthService`, DTO, 예외 클래스 없음).

- [ ] **Step 3: 이름 정규화를 공용으로 분리** — `src/main/java/com/ssen/voca/auth/Names.java`

```java
package com.ssen.voca.auth;

import java.util.Locale;
import java.util.regex.Pattern;

/** 이름의 표시용·식별용 정규화. 학생과 교사가 같은 규칙을 쓴다. */
final class Names {

	// 폭 없는 문자는 눈에 보이지 않으므로 이름 정규화에서 제거한다.
	private static final Pattern INVISIBLE = Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]");
	private static final int MAX_LENGTH = 50;

	private Names() {
	}

	static String display(String name) {
		return INVISIBLE.matcher(name).replaceAll("").strip();
	}

	/** 정규화한 이름 키. 비었거나 50자를 넘으면(소문자화로 길어질 수 있다) 제한 카운터·DB에 닿기 전에 거절한다. */
	static String key(String name) {
		String key = display(name).replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
		if (key.isBlank()) {
			throw new InvalidNameException(InvalidNameException.BLANK);
		}
		if (key.length() > MAX_LENGTH) {
			throw new InvalidNameException(InvalidNameException.TOO_LONG);
		}
		return key;
	}
}
```

`AuthService.java`에서: 필드 `INVISIBLE`, `MAX_NAME_LENGTH`, private 메서드 `displayName`, `nameKey`를 삭제하고, 호출을 `nameKey(request.name())` → `Names.key(request.name())`(가입·로그인 두 곳), `displayName(request.name())` → `Names.display(request.name())`(가입 한 곳)로 바꾼다. 쓰지 않게 된 import(`java.util.Locale`, `java.util.regex.Pattern`)는 지운다.

- [ ] **Step 4: 예외·DTO 작성**

`InvalidCredentialsException.java` 전체:

```java
package com.ssen.voca.auth;

public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("이름 또는 휴대폰 번호가 올바르지 않아요.");
	}

	public InvalidCredentialsException(String message) {
		super(message);
	}
}
```

`InvalidInviteCodeException.java`:

```java
package com.ssen.voca.auth;

public class InvalidInviteCodeException extends RuntimeException {

	public InvalidInviteCodeException() {
		super("교사 가입 코드가 올바르지 않아요.");
	}
}
```

`TeacherEmailExistsException.java`:

```java
package com.ssen.voca.auth;

public class TeacherEmailExistsException extends RuntimeException {

	public TeacherEmailExistsException() {
		super("이미 가입된 이메일이에요. 로그인해 주세요.");
	}
}
```

`InvalidPasswordException.java`:

```java
package com.ssen.voca.auth;

/** 비밀번호가 BCrypt 한도(UTF-8 72바이트)를 넘을 때. 조용히 잘리지 않도록 가입에서 거절한다. */
public class InvalidPasswordException extends RuntimeException {

	public InvalidPasswordException() {
		super("비밀번호가 너무 길어요. 한글 24자, 영문·숫자 72자 이내로 입력해 주세요.");
	}
}
```

`dto/TeacherSignupRequest.java`:

```java
package com.ssen.voca.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeacherSignupRequest(
		@NotBlank(message = "이름을 입력해 주세요")
		@Size(max = 50, message = "이름은 50자 이하로 입력해 주세요")
		String name,
		@NotBlank(message = "이메일을 입력해 주세요")
		@Email(message = "올바른 이메일 형식이 아니에요")
		@Size(max = 255, message = "이메일은 255자 이하로 입력해 주세요")
		String email,
		@NotBlank(message = "비밀번호를 입력해 주세요")
		@Size(min = 8, message = "비밀번호는 8자 이상으로 입력해 주세요")
		String password,
		@NotBlank(message = "교사 가입 코드를 입력해 주세요")
		String inviteCode) {

	// 앞뒤 공백을 제거한 값으로 검증·저장한다 (비밀번호는 그대로 둔다).
	public TeacherSignupRequest {
		email = email == null ? null : email.strip();
		inviteCode = inviteCode == null ? null : inviteCode.strip();
	}
}
```

`dto/TeacherLoginRequest.java`:

```java
package com.ssen.voca.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record TeacherLoginRequest(
		@NotBlank(message = "이메일을 입력해 주세요") String email,
		@NotBlank(message = "비밀번호를 입력해 주세요") String password) {

	public TeacherLoginRequest {
		email = email == null ? null : email.strip();
	}
}
```

- [ ] **Step 5: 서비스와 컨트롤러 작성**

`TeacherAuthService.java`:

```java
package com.ssen.voca.auth;

import com.ssen.voca.auth.dto.TeacherLoginRequest;
import com.ssen.voca.auth.dto.TeacherSignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherAuthService {

	private static final String BAD_CREDENTIALS = "이메일 또는 비밀번호가 올바르지 않아요.";
	// ponytail: dummy BCrypt hash so an unknown email still pays the encoder cost,
	// keeping login timing similar regardless of account existence.
	private static final String DUMMY_SECRET_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
	private static final String INVITE_THROTTLE_KEY = "teacher-signup";
	private static final int MAX_PASSWORD_BYTES = 72;

	private final AppUserRepository appUserRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final LoginAttemptLimiter attemptLimiter;
	private final byte[] inviteCode;

	public TeacherAuthService(
			AppUserRepository appUserRepository,
			PasswordEncoder passwordEncoder,
			JwtService jwtService,
			LoginAttemptLimiter attemptLimiter,
			@Value("${app.teacher.invite-code}") String inviteCode) {
		this.appUserRepository = appUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.attemptLimiter = attemptLimiter;
		this.inviteCode = inviteCode.getBytes(StandardCharsets.UTF_8);
	}

	@Transactional
	public TokenResponse signup(TeacherSignupRequest request) {
		if (tooLong(request.password())) {
			throw new InvalidPasswordException();
		}
		String nameKey = Names.key(request.name());
		// 가입 코드 대입을 막기 위해 코드 검사 전에 선차감하고, 코드가 맞으면 1회를 돌려준다.
		attemptLimiter.tryAcquire(INVITE_THROTTLE_KEY);
		if (!MessageDigest.isEqual(inviteCode, request.inviteCode().getBytes(StandardCharsets.UTF_8))) {
			throw new InvalidInviteCodeException();
		}
		attemptLimiter.release(INVITE_THROTTLE_KEY);

		String email = normalizeEmail(request.email());
		if (appUserRepository.existsByEmail(email)) {
			throw new TeacherEmailExistsException();
		}
		AppUser teacher = AppUser.teacher(
				Names.display(request.name()), nameKey, email, passwordEncoder.encode(request.password()));
		appUserRepository.save(teacher);
		return issueTokens(teacher);
	}

	public TokenResponse login(TeacherLoginRequest request) {
		String email = normalizeEmail(request.email());
		String key = "teacher:" + email;
		attemptLimiter.tryAcquire(key);
		if (tooLong(request.password())) {
			throw new InvalidCredentialsException(BAD_CREDENTIALS);
		}
		AppUser teacher = appUserRepository.findByEmail(email)
				.filter(user -> AppUser.TEACHER.equals(user.getRole()))
				.orElse(null);
		String hash = teacher != null ? teacher.getSecretHash() : DUMMY_SECRET_HASH;
		boolean matches = passwordEncoder.matches(request.password(), hash);
		if (teacher == null || !matches) {
			throw new InvalidCredentialsException(BAD_CREDENTIALS);
		}
		attemptLimiter.release(key);
		return issueTokens(teacher);
	}

	private static boolean tooLong(String password) {
		return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
	}

	private static String normalizeEmail(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	private TokenResponse issueTokens(AppUser user) {
		return new TokenResponse(
				jwtService.generateAccessToken(user.getId(), user.getName(), user.getRole()),
				jwtService.generateRefreshToken(user.getId()));
	}
}
```

`TeacherAuthController.java`:

```java
package com.ssen.voca.auth;

import com.ssen.voca.auth.dto.TeacherLoginRequest;
import com.ssen.voca.auth.dto.TeacherSignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teacher")
public class TeacherAuthController {

	private final TeacherAuthService teacherAuthService;

	public TeacherAuthController(TeacherAuthService teacherAuthService) {
		this.teacherAuthService = teacherAuthService;
	}

	@PostMapping("/signup")
	public ResponseEntity<TokenResponse> signup(@Valid @RequestBody TeacherSignupRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(teacherAuthService.signup(request));
	}

	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody TeacherLoginRequest request) {
		return teacherAuthService.login(request);
	}
}
```

- [ ] **Step 6: 예외 처리기와 설정 수정**

`GlobalExceptionHandler.java`:
- `FIELD_ORDER`를 `List.of("name", "email", "password", "inviteCode", "phoneLast4")`로 바꾼다(주석도 "검증 오류를 필드 순서대로…"에 맞게 필드명 나열을 갱신).
- `handleAlreadyExists` 메서드를 다음으로 교체한다.

```java
	@ExceptionHandler({StudentAlreadyExistsException.class, TeacherEmailExistsException.class})
	public ResponseEntity<Map<String, String>> handleAlreadyExists(RuntimeException e) {
		return message(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler(InvalidInviteCodeException.class)
	public ResponseEntity<Map<String, String>> handleInvalidInviteCode(InvalidInviteCodeException e) {
		return message(HttpStatus.FORBIDDEN, e.getMessage());
	}

	@ExceptionHandler(InvalidPasswordException.class)
	public ResponseEntity<Map<String, String>> handleInvalidPassword(InvalidPasswordException e) {
		return message(HttpStatus.BAD_REQUEST, e.getMessage());
	}
```
(필요한 import: `com.ssen.voca.auth.InvalidInviteCodeException`, `InvalidPasswordException`, `TeacherEmailExistsException`)

`application.yml`:
- 루트 `app:` 블록(`cors`, `auth`와 같은 들여쓰기)에 추가:

```yaml
  teacher:
    invite-code: ${TEACHER_INVITE_CODE}
```
- `local` 프로필의 `app:` 블록(`jwt`, `cors`와 같은 들여쓰기)에 추가:

```yaml
  teacher:
    invite-code: ${TEACHER_INVITE_CODE:local-teacher-code}
```

- [ ] **Step 7: 전체 테스트 통과 확인**

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test`
Expected: BUILD SUCCESSFUL. 학생 가입·로그인·시도 제한 테스트는 `Names` 분리 후에도 그대로 통과해야 한다.

- [ ] **Step 8: 커밋**

```bash
git add src/main src/test
git commit -m "feat: 교사 가입(가입 코드)·로그인 API 추가 (#11)"
```

---

### Task 4: 수업 생성 · 목록과 참여코드 발급, 내 정보 이메일, 문서

**Files:**
- Create: `src/main/java/com/ssen/voca/classroom/Classroom.java`, `ClassroomRepository.java`, `ClassCodeGenerator.java`, `ClassroomService.java`, `TeacherClassroomController.java`, `dto/CreateClassroomRequest.java`, `dto/ClassroomResponse.java`
- Modify: `src/main/java/com/ssen/voca/user/dto/UserResponse.java`, `src/main/java/com/ssen/voca/user/UserController.java`, `README.md`, `.env.example`, `docs/superpowers/specs/2026-09-30-classroom-wordbooks-design.md`
- Test: `src/test/java/com/ssen/voca/classroom/ClassCodeGeneratorTest.java`, `ClassroomServiceTest.java`, `TeacherClassroomControllerTest.java`, `src/test/java/com/ssen/voca/user/UserControllerTest.java`

**Interfaces:**
- Consumes: `AppUser.teacher(...)`, `AppUserRepository` (Task 1), 교사 토큰·권한 (Task 2·3), `POST /api/teacher/signup`
- Produces: `POST /api/teacher/classrooms {name}` → 201 `{id, name, code, createdAt}`, `GET /api/teacher/classrooms` → 내 수업 배열(최신순). `GET /api/users/me`는 `email`을 포함한다(학생은 `null`). `ClassCodeGenerator.generate(): String`, `ClassroomService.create(Long teacherId, String name): Classroom`, `listOf(Long teacherId): List<Classroom>`, `ClassroomRepository.findByCode`는 만들지 않는다(조각 2에서 추가).

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/ssen/voca/classroom/ClassCodeGeneratorTest.java`

```java
package com.ssen.voca.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassCodeGeneratorTest {

	private final ClassCodeGenerator generator = new ClassCodeGenerator();

	@Test
	void codesAreSixCharsFromTheUnambiguousAlphabet() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 2000; i++) {
			String code = generator.generate();
			assertThat(code).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}");
			seen.add(code);
		}
		// 31^6 가지에서 2000개를 뽑으면 거의 겹치지 않는다 (상수로 고정된 값이 아닌지 확인).
		assertThat(seen.size()).isGreaterThan(1990);
	}
}
```

`src/test/java/com/ssen/voca/classroom/ClassroomServiceTest.java`

```java
package com.ssen.voca.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ClassroomServiceTest {

	@Autowired
	private ClassroomService classroomService;

	@Autowired
	private AppUserRepository appUserRepository;

	@MockitoSpyBean
	private ClassCodeGenerator codeGenerator;

	private Long newTeacherId(String email) {
		return appUserRepository.save(AppUser.teacher("서비스교사", "서비스교사", email, "hash")).getId();
	}

	@Test
	void createRetriesWhenTheCodeIsTaken() {
		Long teacherId = newTeacherId("svc-retry@example.com");
		doReturn("AAAAAA").when(codeGenerator).generate();
		Classroom first = classroomService.create(teacherId, "첫 수업");
		assertThat(first.getCode()).isEqualTo("AAAAAA");

		doReturn("AAAAAA", "AAAAAA", "BBBBBB").when(codeGenerator).generate();
		Classroom second = classroomService.create(teacherId, "둘째 수업");

		assertThat(second.getCode()).isEqualTo("BBBBBB");
	}

	@Test
	void createGivesUpAfterFiveCollisions() {
		Long teacherId = newTeacherId("svc-giveup@example.com");
		doReturn("CCCCCC").when(codeGenerator).generate();
		classroomService.create(teacherId, "선점 수업");

		assertThatThrownBy(() -> classroomService.create(teacherId, "충돌 수업"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void listOfReturnsOnlyOwnClassroomsNewestFirst() {
		Long teacherA = newTeacherId("svc-list-a@example.com");
		Long teacherB = newTeacherId("svc-list-b@example.com");
		classroomService.create(teacherA, "A-1");
		classroomService.create(teacherB, "B-1");
		classroomService.create(teacherA, "A-2");

		assertThat(classroomService.listOf(teacherA)).extracting(Classroom::getName).containsExactly("A-2", "A-1");
		assertThat(classroomService.listOf(teacherB)).extracting(Classroom::getName).containsExactly("B-1");
	}
}
```

`src/test/java/com/ssen/voca/classroom/TeacherClassroomControllerTest.java`

```java
package com.ssen.voca.classroom;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
		String studentToken = postForJson("/api/auth/signup", Map.of("name", "수업학생", "phoneLast4", "1234"))
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
```

`src/test/java/com/ssen/voca/user/UserControllerTest.java`에 테스트 두 개를 더한다(파일 상단 import에 `org.hamcrest.Matchers.nullValue`를 정적 import로, `java.util.Map`, `org.springframework.beans.factory.annotation.Value`, `org.springframework.http.HttpHeaders`를 추가한다. 기존 `mockMvc`, `objectMapper`, `signupAndGetAccessToken` 필드·헬퍼를 그대로 쓴다).

```java
	@Value("${app.teacher.invite-code}")
	private String inviteCode;

	@Test
	void teacherMeIncludesEmailAndRole() throws Exception {
		String response = mockMvc.perform(post("/api/teacher/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of(
								"name", "내정보교사", "email", "me-teacher@example.com",
								"password", "password1", "inviteCode", inviteCode))))
				.andReturn().getResponse().getContentAsString();
		String token = objectMapper.readTree(response).get("accessToken").asText();

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("TEACHER"))
				.andExpect(jsonPath("$.email").value("me-teacher@example.com"));
	}

	@Test
	void studentMeHasNullEmail() throws Exception {
		String token = signupAndGetAccessToken("내정보학생이메일");

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("STUDENT"))
				.andExpect(jsonPath("$.email").value(nullValue()));
	}
```

- [ ] **Step 2: 실패 확인**

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test --tests '*ClassCodeGeneratorTest' --tests '*ClassroomServiceTest' --tests '*TeacherClassroomControllerTest' --tests '*UserControllerTest'`
Expected: 컴파일 FAIL (`classroom` 패키지 클래스 없음, `UserResponse.email` 없음).

- [ ] **Step 3: 수업 도메인 구현**

`classroom/Classroom.java`:

```java
package com.ssen.voca.classroom;

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
@Table(name = "classroom")
@Getter
@NoArgsConstructor
public class Classroom {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "teacher_id", nullable = false)
	private Long teacherId;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 6, unique = true)
	private String code;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public Classroom(Long teacherId, String name, String code) {
		this.teacherId = teacherId;
		this.name = name;
		this.code = code;
		this.createdAt = LocalDateTime.now();
	}
}
```

`classroom/ClassroomRepository.java`:

```java
package com.ssen.voca.classroom;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassroomRepository extends JpaRepository<Classroom, Long> {

	List<Classroom> findAllByTeacherIdOrderByIdDesc(Long teacherId);

	boolean existsByCode(String code);
}
```

`classroom/ClassCodeGenerator.java`:

```java
package com.ssen.voca.classroom;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 참여코드 생성기. 0/O, 1/I/L처럼 헷갈리는 글자를 뺀 31자에서 6자리를 뽑는다. */
@Component
public class ClassCodeGenerator {

	static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
	static final int LENGTH = 6;

	private final SecureRandom random = new SecureRandom();

	public String generate() {
		StringBuilder code = new StringBuilder(LENGTH);
		for (int i = 0; i < LENGTH; i++) {
			code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return code.toString();
	}
}
```

`classroom/ClassroomService.java`:

```java
package com.ssen.voca.classroom;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassroomService {

	private static final int MAX_CODE_ATTEMPTS = 5;

	private final ClassroomRepository classroomRepository;
	private final ClassCodeGenerator codeGenerator;

	public ClassroomService(ClassroomRepository classroomRepository, ClassCodeGenerator codeGenerator) {
		this.classroomRepository = classroomRepository;
		this.codeGenerator = codeGenerator;
	}

	@Transactional
	public Classroom create(Long teacherId, String name) {
		for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
			String code = codeGenerator.generate();
			if (!classroomRepository.existsByCode(code)) {
				// ponytail: exists 확인과 저장 사이의 경합은 UNIQUE 제약이 막고 그 요청만 500이 된다. 31^6 가지라 사실상 일어나지 않는다.
				return classroomRepository.save(new Classroom(teacherId, name, code));
			}
		}
		throw new IllegalStateException("참여 코드를 만들지 못했어요");
	}

	@Transactional(readOnly = true)
	public List<Classroom> listOf(Long teacherId) {
		return classroomRepository.findAllByTeacherIdOrderByIdDesc(teacherId);
	}
}
```

`classroom/dto/CreateClassroomRequest.java`:

```java
package com.ssen.voca.classroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClassroomRequest(
		@NotBlank(message = "수업 이름을 입력해 주세요")
		@Size(max = 100, message = "수업 이름은 100자 이하로 입력해 주세요")
		String name) {

	// 앞뒤 공백(전각 공백 포함)을 제거한 값으로 검증·저장한다.
	public CreateClassroomRequest {
		name = name == null ? null : name.strip();
	}
}
```

`classroom/dto/ClassroomResponse.java`:

```java
package com.ssen.voca.classroom.dto;

import com.ssen.voca.classroom.Classroom;
import java.time.LocalDateTime;

public record ClassroomResponse(Long id, String name, String code, LocalDateTime createdAt) {

	public static ClassroomResponse from(Classroom classroom) {
		return new ClassroomResponse(
				classroom.getId(), classroom.getName(), classroom.getCode(), classroom.getCreatedAt());
	}
}
```

`classroom/TeacherClassroomController.java`:

```java
package com.ssen.voca.classroom;

import com.ssen.voca.classroom.dto.ClassroomResponse;
import com.ssen.voca.classroom.dto.CreateClassroomRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teacher/classrooms")
public class TeacherClassroomController {

	private final ClassroomService classroomService;

	public TeacherClassroomController(ClassroomService classroomService) {
		this.classroomService = classroomService;
	}

	@PostMapping
	public ResponseEntity<ClassroomResponse> create(
			Authentication authentication, @Valid @RequestBody CreateClassroomRequest request) {
		Classroom classroom = classroomService.create(teacherId(authentication), request.name());
		return ResponseEntity.status(HttpStatus.CREATED).body(ClassroomResponse.from(classroom));
	}

	@GetMapping
	public List<ClassroomResponse> list(Authentication authentication) {
		return classroomService.listOf(teacherId(authentication)).stream().map(ClassroomResponse::from).toList();
	}

	private static Long teacherId(Authentication authentication) {
		return Long.valueOf(authentication.getName());
	}
}
```

- [ ] **Step 4: 내 정보에 이메일 추가**

`user/dto/UserResponse.java` 전체:

```java
package com.ssen.voca.user.dto;

public record UserResponse(Long id, String name, String classCode, String role, String email) {
}
```

`user/UserController.java`의 `new UserResponse(user.getId(), user.getName(), user.getClassCode(), user.getRole())` 두 곳(`me`, `updateClassCode`)에 마지막 인자 `user.getEmail()`을 더한다.

- [ ] **Step 5: 전체 테스트 통과 확인**

Run: `DB_PORT=5433 DB_PASSWORD=ssen ./gradlew test`
Expected: BUILD SUCCESSFUL, 모든 테스트 통과.

- [ ] **Step 6: 문서 갱신**

`.env.example`에 추가:

```
# 교사 가입 코드 — 교사 가입 시 입력해야 하는 코드. local 프로필은 기본값(local-teacher-code)이 있고, 운영에서는 필수 지정.
TEACHER_INVITE_CODE=change-me-teacher-code
```

`README.md` — `## 인증 API` 절의 학생 표 아래(`### CORS` 앞)에 다음 절을 더하고, 표의 `/api/users/me` 행을 `{id, name, classCode, role, email}`로 고친다.

```markdown
### 교사 API

교사는 **이메일 + 비밀번호**로 로그인하고, 가입할 때 **교사 가입 코드**(`TEACHER_INVITE_CODE`)가 필요합니다. 교사 API는 액세스 토큰의 role이 `TEACHER`일 때만 쓸 수 있습니다(학생 토큰은 403).

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/teacher/signup` | 교사 가입 `{name, email, password, inviteCode}` → 201 `{accessToken, refreshToken}`. 코드 불일치 403, 이메일 중복 409 |
| POST | `/api/teacher/login` | 로그인 `{email, password}` → 200 `{accessToken, refreshToken}`. 불일치 401 |
| POST | `/api/teacher/classrooms` | 수업 생성 `{name}` → 201 `{id, name, code, createdAt}`. `code`는 6자리 참여코드 |
| GET | `/api/teacher/classrooms` | 내 수업 목록(최신순) |

- 이메일은 앞뒤 공백 제거 · 소문자로 저장·비교합니다. 비밀번호는 8자 이상, UTF-8 72바이트 이하(BCrypt 한도)이며 BCrypt 해시로만 저장합니다.
- 가입 코드 입력은 `teacher-signup` 키 하나로 5분에 5회까지만 틀릴 수 있고(맞게 입력하면 그 1회는 돌려줍니다), 교사 로그인은 이메일 기준으로 같은 규칙입니다. 넘으면 429입니다.
- 참여코드는 0/O, 1/I/L을 뺀 31자로 만든 6자리입니다. 교사는 자기 수업만 볼 수 있습니다.
- 리프레시(`/api/auth/refresh`)는 교사·학생 공통이고, 새 액세스 토큰에는 DB의 최신 role이 담깁니다.
```

`docs/superpowers/specs/2026-09-30-classroom-wordbooks-design.md` — API 표의 두 행을 바꾼다(`wordCount`는 단어 테이블이 생기는 1b에서 더한다).

- `201 \`{id, name, code, wordCount, createdAt}\`` → `201 \`{id, name, code, createdAt}\``
- `내 수업 목록(wordCount 포함)` → `내 수업 목록(최신순, wordCount는 1b에서 추가)`

- [ ] **Step 7: 커밋**

```bash
git add src/main src/test README.md .env.example docs/superpowers/specs/2026-09-30-classroom-wordbooks-design.md
git commit -m "feat: 수업 생성·목록 API와 참여코드 발급 추가 (#11)"
```
