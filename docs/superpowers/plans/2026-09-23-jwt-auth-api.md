# 계정·로그인 API (JWT) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 이메일+비밀번호 회원가입/로그인과 JWT(Access+Refresh) 인증, 참여코드 등록을 제공하는 API를 ssen-server에 구현한다.

**Architecture:** `auth` 패키지(토큰 발급/검증, 회원가입/로그인 비즈니스 로직, 인증 필터)와 `user` 패키지(엔티티, 레포지토리, 내 정보/참여코드 컨트롤러)로 나눈다. 인증은 완전 stateless — DB에 토큰을 저장하지 않고, 서명된 JWT만으로 access/refresh를 구분한다.

**Tech Stack:** Spring Boot 3.5.9 (Java 21), Spring Security 6 (stateless), `io.jsonwebtoken:jjwt` 0.12.6, Spring Data JPA + PostgreSQL 16 + Flyway, Lombok, JUnit 5 + AssertJ + MockMvc.

**Spec:** `docs/superpowers/specs/2026-09-23-jwt-auth-design.md`

## Global Constraints

- Java 21 / Spring Boot 3.5.9 툴체인 고정 (변경 금지).
- 모든 `@SpringBootTest`는 실제 로컬 PostgreSQL(도커)에 연결한다 — 새 테스트 의존성(Testcontainers, H2 등) 추가 금지. 테스트/빌드 전에 저장소 루트에서 `docker compose up -d`를 실행해 DB가 떠 있어야 한다.
- `JWT_SECRET`은 HS256 최소 요구 길이인 32바이트 이상이어야 한다(jjwt `WeakKeyException` 방지). 기존 `.env.example`/기본값 `change-me-to-a-long-random-string`(33바이트)을 그대로 쓰고 줄이지 않는다.
- 들여쓰기는 기존 파일(`HealthController.java` 등)과 동일하게 **탭(tab)** 을 사용한다.
- 이번 스코프는 학생 로그인만 다룬다. `role` 컬럼은 두되 항상 `"STUDENT"`로 고정 — 권한 분기 로직을 만들지 않는다.
- 리프레시 토큰 로테이션·DB 기반 강제 무효화는 범위 밖이다(완전 stateless, 설계 문서의 트레이드오프 참고).

---

### Task 1: Security 의존성 + 기본 SecurityConfig

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/java/com/ssen/voca/auth/SecurityConfig.java`
- Test: `src/test/java/com/ssen/voca/common/HealthControllerTest.java`

**Interfaces:**
- Produces: `SecurityConfig` — `PasswordEncoder passwordEncoder()` 빈(다음 태스크들이 사용), `/api/health`·`/api/auth/**` permitAll, 그 외 인증 필요.

- [ ] **Step 1: build.gradle.kts에 의존성 추가**

`dependencies { ... }` 블록을 아래 내용으로 교체한다:

```kotlin
dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("io.jsonwebtoken:jjwt-api:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
	runtimeOnly("org.postgresql:postgresql")
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.security:spring-security-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

- [ ] **Step 2: 실패하는 테스트 작성 (HealthControllerTest)**

```java
package com.ssen.voca.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void healthEndpointIsPubliclyAccessible() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk());
	}
}
```

- [ ] **Step 3: 테스트 실행 → 실패 확인**

먼저 `docker compose up -d`로 로컬 PostgreSQL을 띄운 뒤 실행한다.

Run: `./gradlew test --tests "com.ssen.voca.common.HealthControllerTest"`
Expected: **FAIL** (401) — `spring-boot-starter-security`를 추가하면 Spring Security 기본 설정이 모든 요청에 인증을 요구하기 때문.

- [ ] **Step 4: SecurityConfig 구현**

```java
package com.ssen.voca.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/health", "/api/auth/**").permitAll()
						.anyRequest().authenticated());

		return http.build();
	}
}
```

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.ssen.voca.common.HealthControllerTest"`
Expected: **PASS**

- [ ] **Step 6: 커밋**

```bash
git add build.gradle.kts src/main/java/com/ssen/voca/auth/SecurityConfig.java src/test/java/com/ssen/voca/common/HealthControllerTest.java
git commit -m "feat: Spring Security 의존성 및 기본 인가 규칙 추가 (#5)"
```

---

### Task 2: `app_user` 테이블 + 엔티티 + 레포지토리

**Files:**
- Create: `src/main/resources/db/migration/V2__create_app_user.sql`
- Create: `src/main/java/com/ssen/voca/user/AppUser.java`
- Create: `src/main/java/com/ssen/voca/user/AppUserRepository.java`
- Test: `src/test/java/com/ssen/voca/user/AppUserRepositoryTest.java`

**Interfaces:**
- Produces: `AppUser(String name, String email, String passwordHash)` 생성자(role="STUDENT" 자동 설정), `getId/getName/getEmail/getPasswordHash/getClassCode/getRole/getCreatedAt()`, `void updateClassCode(String)`. `AppUserRepository`: `Optional<AppUser> findByEmail(String)`, `boolean existsByEmail(String)` (+ JpaRepository 표준 메서드).

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.ssen.voca.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AppUserRepositoryTest {

	@Autowired
	private AppUserRepository appUserRepository;

	@Test
	void savesAndFindsByEmail() {
		AppUser user = new AppUser("김학생", "student@example.com", "hashed-password");

		appUserRepository.save(user);

		assertThat(appUserRepository.findByEmail("student@example.com"))
				.isPresent()
				.get()
				.extracting(AppUser::getName, AppUser::getRole)
				.containsExactly("김학생", "STUDENT");
	}

	@Test
	void existsByEmailReturnsFalseForUnknownEmail() {
		assertThat(appUserRepository.existsByEmail("nobody@example.com")).isFalse();
	}
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.ssen.voca.user.AppUserRepositoryTest"`
Expected: **FAIL** (컴파일 에러 — `AppUser`, `AppUserRepository` 클래스가 없음)

- [ ] **Step 3: Flyway 마이그레이션 작성**

```sql
CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(50) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    class_code    VARCHAR(50),
    role          VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
```

- [ ] **Step 4: AppUser 엔티티 작성**

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

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String name;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "class_code", length = 50)
	private String classCode;

	@Column(nullable = false, length = 20)
	private String role;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public AppUser(String name, String email, String passwordHash) {
		this.name = name;
		this.email = email;
		this.passwordHash = passwordHash;
		this.role = "STUDENT";
		this.createdAt = LocalDateTime.now();
	}

	public void updateClassCode(String classCode) {
		this.classCode = classCode;
	}
}
```

- [ ] **Step 5: AppUserRepository 작성**

```java
package com.ssen.voca.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	Optional<AppUser> findByEmail(String email);

	boolean existsByEmail(String email);
}
```

- [ ] **Step 6: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.ssen.voca.user.AppUserRepositoryTest"`
Expected: **PASS**

- [ ] **Step 7: 커밋**

```bash
git add src/main/resources/db/migration/V2__create_app_user.sql src/main/java/com/ssen/voca/user/AppUser.java src/main/java/com/ssen/voca/user/AppUserRepository.java src/test/java/com/ssen/voca/user/AppUserRepositoryTest.java
git commit -m "feat: app_user 테이블 및 엔티티·레포지토리 추가 (#5)"
```

---

### Task 3: JwtService (Access/Refresh 발급·검증)

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/ssen/voca/auth/InvalidTokenException.java`
- Create: `src/main/java/com/ssen/voca/auth/JwtService.java`
- Test: `src/test/java/com/ssen/voca/auth/JwtServiceTest.java`

**Interfaces:**
- Produces: `JwtService(String secret, long accessTtlMillis, long refreshTtlMillis)`, `String generateAccessToken(Long userId, String email)`, `String generateRefreshToken(Long userId)`, `Claims parseAccessToken(String token)`, `Claims parseRefreshToken(String token)` — 둘 다 실패 시 `InvalidTokenException` 던짐. `InvalidTokenException(String message)`.

- [ ] **Step 1: application.yml에 JWT 설정 추가**

`application.yml`의 최상단(`spring:` 블록 앞이나 뒤, 첫 번째 `---` 이전)에 아래를 추가한다:

```yaml
app:
  jwt:
    secret: ${JWT_SECRET:change-me-to-a-long-random-string}
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

	private final JwtService jwtService =
			new JwtService("test-secret-key-that-is-at-least-32-bytes-long-1234567890", 1_800_000L, 1_209_600_000L);

	@Test
	void generatesAndParsesAccessToken() {
		String token = jwtService.generateAccessToken(1L, "student@example.com");

		Claims claims = jwtService.parseAccessToken(token);

		assertThat(claims.getSubject()).isEqualTo("1");
		assertThat(claims.get("email")).isEqualTo("student@example.com");
	}

	@Test
	void generatesAndParsesRefreshToken() {
		String token = jwtService.generateRefreshToken(1L);

		Claims claims = jwtService.parseRefreshToken(token);

		assertThat(claims.getSubject()).isEqualTo("1");
	}

	@Test
	void rejectsRefreshTokenAsAccessToken() {
		String refreshToken = jwtService.generateRefreshToken(1L);

		assertThatThrownBy(() -> jwtService.parseAccessToken(refreshToken))
				.isInstanceOf(InvalidTokenException.class);
	}

	@Test
	void rejectsAccessTokenAsRefreshToken() {
		String accessToken = jwtService.generateAccessToken(1L, "student@example.com");

		assertThatThrownBy(() -> jwtService.parseRefreshToken(accessToken))
				.isInstanceOf(InvalidTokenException.class);
	}

	@Test
	void rejectsGarbageToken() {
		assertThatThrownBy(() -> jwtService.parseAccessToken("not-a-jwt"))
				.isInstanceOf(InvalidTokenException.class);
	}
}
```

- [ ] **Step 3: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.ssen.voca.auth.JwtServiceTest"`
Expected: **FAIL** (컴파일 에러 — `JwtService`, `InvalidTokenException` 없음)

- [ ] **Step 4: InvalidTokenException 작성**

```java
package com.ssen.voca.auth;

public class InvalidTokenException extends RuntimeException {

	public InvalidTokenException(String message) {
		super(message);
	}
}
```

- [ ] **Step 5: JwtService 구현**

```java
package com.ssen.voca.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {

	private static final String TYPE_CLAIM = "type";
	private static final String REFRESH_TYPE = "refresh";

	private final SecretKey key;
	private final long accessTokenTtlMillis;
	private final long refreshTokenTtlMillis;

	public JwtService(
			@Value("${app.jwt.secret}") String secret,
			@Value("${app.jwt.access-ttl-millis:1800000}") long accessTokenTtlMillis,
			@Value("${app.jwt.refresh-ttl-millis:1209600000}") long refreshTokenTtlMillis) {
		this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		this.accessTokenTtlMillis = accessTokenTtlMillis;
		this.refreshTokenTtlMillis = refreshTokenTtlMillis;
	}

	public String generateAccessToken(Long userId, String email) {
		Date now = new Date();
		return Jwts.builder()
				.subject(String.valueOf(userId))
				.claim("email", email)
				.issuedAt(now)
				.expiration(new Date(now.getTime() + accessTokenTtlMillis))
				.signWith(key)
				.compact();
	}

	public String generateRefreshToken(Long userId) {
		Date now = new Date();
		return Jwts.builder()
				.subject(String.valueOf(userId))
				.claim(TYPE_CLAIM, REFRESH_TYPE)
				.issuedAt(now)
				.expiration(new Date(now.getTime() + refreshTokenTtlMillis))
				.signWith(key)
				.compact();
	}

	public Claims parseAccessToken(String token) {
		Claims claims = parse(token);
		if (claims.get(TYPE_CLAIM) != null) {
			throw new InvalidTokenException("액세스 토큰이 아닙니다.");
		}
		return claims;
	}

	public Claims parseRefreshToken(String token) {
		Claims claims = parse(token);
		if (!REFRESH_TYPE.equals(claims.get(TYPE_CLAIM))) {
			throw new InvalidTokenException("리프레시 토큰이 아닙니다.");
		}
		return claims;
	}

	private Claims parse(String token) {
		try {
			return Jwts.parser()
					.verifyWith(key)
					.build()
					.parseSignedClaims(token)
					.getPayload();
		} catch (JwtException | IllegalArgumentException e) {
			throw new InvalidTokenException("유효하지 않은 토큰입니다.");
		}
	}
}
```

- [ ] **Step 6: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.ssen.voca.auth.JwtServiceTest"`
Expected: **PASS**

- [ ] **Step 7: 커밋**

```bash
git add src/main/resources/application.yml src/main/java/com/ssen/voca/auth/InvalidTokenException.java src/main/java/com/ssen/voca/auth/JwtService.java src/test/java/com/ssen/voca/auth/JwtServiceTest.java
git commit -m "feat: JWT Access/Refresh 토큰 발급·검증 서비스 추가 (#5)"
```

---

### Task 4: AuthService (회원가입·로그인·재발급 로직)

**Files:**
- Create: `src/main/java/com/ssen/voca/auth/dto/SignupRequest.java`
- Create: `src/main/java/com/ssen/voca/auth/dto/LoginRequest.java`
- Create: `src/main/java/com/ssen/voca/auth/dto/RefreshRequest.java`
- Create: `src/main/java/com/ssen/voca/auth/dto/TokenResponse.java`
- Create: `src/main/java/com/ssen/voca/auth/dto/AccessTokenResponse.java`
- Create: `src/main/java/com/ssen/voca/auth/EmailAlreadyExistsException.java`
- Create: `src/main/java/com/ssen/voca/auth/InvalidCredentialsException.java`
- Create: `src/main/java/com/ssen/voca/auth/AuthService.java`
- Test: `src/test/java/com/ssen/voca/auth/AuthServiceTest.java`

**Interfaces:**
- Consumes: `AppUserRepository`(Task 2), `PasswordEncoder`(Task 1), `JwtService`(Task 3).
- Produces: `AuthService.signup(SignupRequest)/login(LoginRequest) -> TokenResponse`, `AuthService.refresh(RefreshRequest) -> AccessTokenResponse`. 실패 시 `EmailAlreadyExistsException`/`InvalidCredentialsException`/`InvalidTokenException`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.RefreshRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AuthServiceTest {

	@Autowired
	private AuthService authService;

	@Autowired
	private JwtService jwtService;

	@Test
	void signupCreatesUserAndReturnsTokens() {
		TokenResponse tokens = authService.signup(new SignupRequest("김학생", "new-student@example.com", "password123"));

		assertThat(jwtService.parseAccessToken(tokens.accessToken()).get("email")).isEqualTo("new-student@example.com");
		assertThat(jwtService.parseRefreshToken(tokens.refreshToken())).isNotNull();
	}

	@Test
	void signupWithDuplicateEmailThrows() {
		authService.signup(new SignupRequest("김학생", "dup@example.com", "password123"));

		assertThatThrownBy(() -> authService.signup(new SignupRequest("이학생", "dup@example.com", "password456")))
				.isInstanceOf(EmailAlreadyExistsException.class);
	}

	@Test
	void loginWithCorrectPasswordReturnsTokens() {
		authService.signup(new SignupRequest("김학생", "login@example.com", "password123"));

		TokenResponse tokens = authService.login(new LoginRequest("login@example.com", "password123"));

		assertThat(jwtService.parseAccessToken(tokens.accessToken())).isNotNull();
	}

	@Test
	void loginWithWrongPasswordThrows() {
		authService.signup(new SignupRequest("김학생", "wrongpw@example.com", "password123"));

		assertThatThrownBy(() -> authService.login(new LoginRequest("wrongpw@example.com", "wrong-password")))
				.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void refreshWithValidRefreshTokenReturnsNewAccessToken() {
		TokenResponse tokens = authService.signup(new SignupRequest("김학생", "refresh@example.com", "password123"));

		var accessTokenResponse = authService.refresh(new RefreshRequest(tokens.refreshToken()));

		assertThat(jwtService.parseAccessToken(accessTokenResponse.accessToken()).get("email"))
				.isEqualTo("refresh@example.com");
	}

	@Test
	void refreshWithAccessTokenThrows() {
		TokenResponse tokens = authService.signup(new SignupRequest("김학생", "badrefresh@example.com", "password123"));

		assertThatThrownBy(() -> authService.refresh(new RefreshRequest(tokens.accessToken())))
				.isInstanceOf(InvalidTokenException.class);
	}
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.ssen.voca.auth.AuthServiceTest"`
Expected: **FAIL** (컴파일 에러 — `AuthService`와 DTO들이 없음)

- [ ] **Step 3: DTO 작성**

```java
package com.ssen.voca.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
		@NotBlank String name,
		@NotBlank @Email String email,
		@NotBlank @Size(min = 8) String password) {
}
```

```java
package com.ssen.voca.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String email, @NotBlank String password) {
}
```

```java
package com.ssen.voca.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(@NotBlank String refreshToken) {
}
```

```java
package com.ssen.voca.auth.dto;

public record TokenResponse(String accessToken, String refreshToken) {
}
```

```java
package com.ssen.voca.auth.dto;

public record AccessTokenResponse(String accessToken) {
}
```

- [ ] **Step 4: 예외 클래스 작성**

```java
package com.ssen.voca.auth;

public class EmailAlreadyExistsException extends RuntimeException {

	public EmailAlreadyExistsException(String email) {
		super("이미 가입된 이메일입니다: " + email);
	}
}
```

```java
package com.ssen.voca.auth;

public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("이메일 또는 비밀번호가 올바르지 않습니다.");
	}
}
```

- [ ] **Step 5: AuthService 구현**

```java
package com.ssen.voca.auth;

import com.ssen.voca.auth.dto.AccessTokenResponse;
import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.RefreshRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private final AppUserRepository appUserRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;

	public AuthService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
		this.appUserRepository = appUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
	}

	@Transactional
	public TokenResponse signup(SignupRequest request) {
		if (appUserRepository.existsByEmail(request.email())) {
			throw new EmailAlreadyExistsException(request.email());
		}

		AppUser user = new AppUser(request.name(), request.email(), passwordEncoder.encode(request.password()));
		appUserRepository.save(user);

		return issueTokens(user);
	}

	public TokenResponse login(LoginRequest request) {
		AppUser user = appUserRepository.findByEmail(request.email())
				.orElseThrow(InvalidCredentialsException::new);

		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new InvalidCredentialsException();
		}

		return issueTokens(user);
	}

	public AccessTokenResponse refresh(RefreshRequest request) {
		Long userId = Long.valueOf(jwtService.parseRefreshToken(request.refreshToken()).getSubject());
		AppUser user = appUserRepository.findById(userId)
				.orElseThrow(() -> new InvalidTokenException("존재하지 않는 사용자입니다."));

		return new AccessTokenResponse(jwtService.generateAccessToken(user.getId(), user.getEmail()));
	}

	private TokenResponse issueTokens(AppUser user) {
		return new TokenResponse(
				jwtService.generateAccessToken(user.getId(), user.getEmail()),
				jwtService.generateRefreshToken(user.getId()));
	}
}
```

- [ ] **Step 6: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.ssen.voca.auth.AuthServiceTest"`
Expected: **PASS**

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/ssen/voca/auth/dto src/main/java/com/ssen/voca/auth/EmailAlreadyExistsException.java src/main/java/com/ssen/voca/auth/InvalidCredentialsException.java src/main/java/com/ssen/voca/auth/AuthService.java src/test/java/com/ssen/voca/auth/AuthServiceTest.java
git commit -m "feat: 회원가입·로그인·토큰 재발급 서비스 로직 추가 (#5)"
```

---

### Task 5: AuthController + 예외 → HTTP 상태 매핑

**Files:**
- Create: `src/main/java/com/ssen/voca/auth/AuthController.java`
- Create: `src/main/java/com/ssen/voca/common/GlobalExceptionHandler.java`
- Test: `src/test/java/com/ssen/voca/auth/AuthControllerTest.java`

**Interfaces:**
- Consumes: `AuthService`(Task 4).
- Produces: `POST /api/auth/signup`(201), `POST /api/auth/login`(200), `POST /api/auth/refresh`(200) 엔드포인트. `GlobalExceptionHandler`가 `EmailAlreadyExistsException→409`, `{InvalidCredentialsException, InvalidTokenException}→401`로 매핑.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.ssen.voca.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.RefreshRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void signupReturns201WithTokens() throws Exception {
		mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new SignupRequest("김학생", "controller-signup@example.com", "password123"))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test
	void signupWithDuplicateEmailReturns409() throws Exception {
		SignupRequest request = new SignupRequest("김학생", "dup-controller@example.com", "password123");

		mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)));

		mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new SignupRequest("이학생", "dup-controller@example.com", "password456"))))
				.andExpect(status().isConflict());
	}

	@Test
	void loginWithCorrectPasswordReturns200WithTokens() throws Exception {
		mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
						new SignupRequest("김학생", "login-controller@example.com", "password123"))));

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new LoginRequest("login-controller@example.com", "password123"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test
	void loginWithWrongPasswordReturns401() throws Exception {
		mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
						new SignupRequest("김학생", "wrongpw-controller@example.com", "password123"))));

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new LoginRequest("wrongpw-controller@example.com", "wrong-password"))))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void refreshWithRefreshTokenReturns200WithNewAccessToken() throws Exception {
		MvcResult signupResult = mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new SignupRequest("김학생", "refresh-controller@example.com", "password123"))))
				.andReturn();
		String refreshToken = objectMapper.readTree(signupResult.getResponse().getContentAsString())
				.get("refreshToken").asText();

		mockMvc.perform(post("/api/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test
	void refreshWithAccessTokenReturns401() throws Exception {
		MvcResult signupResult = mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new SignupRequest("김학생", "badrefresh-controller@example.com", "password123"))))
				.andReturn();
		String accessToken = objectMapper.readTree(signupResult.getResponse().getContentAsString())
				.get("accessToken").asText();

		mockMvc.perform(post("/api/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshRequest(accessToken))))
				.andExpect(status().isUnauthorized());
	}
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.ssen.voca.auth.AuthControllerTest"`
Expected: **FAIL** (404 — `/api/auth/*` 엔드포인트 없음)

- [ ] **Step 3: AuthController 구현**

```java
package com.ssen.voca.auth;

import com.ssen.voca.auth.dto.AccessTokenResponse;
import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.RefreshRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/signup")
	public ResponseEntity<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request));
	}

	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	@PostMapping("/refresh")
	public AccessTokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return authService.refresh(request);
	}
}
```

- [ ] **Step 4: GlobalExceptionHandler 구현**

```java
package com.ssen.voca.common;

import com.ssen.voca.auth.EmailAlreadyExistsException;
import com.ssen.voca.auth.InvalidCredentialsException;
import com.ssen.voca.auth.InvalidTokenException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(EmailAlreadyExistsException.class)
	public ResponseEntity<Map<String, String>> handleEmailAlreadyExists(EmailAlreadyExistsException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
	}

	@ExceptionHandler({InvalidCredentialsException.class, InvalidTokenException.class})
	public ResponseEntity<Map<String, String>> handleUnauthorized(RuntimeException e) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", e.getMessage()));
	}
}
```

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.ssen.voca.auth.AuthControllerTest"`
Expected: **PASS**

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/ssen/voca/auth/AuthController.java src/main/java/com/ssen/voca/common/GlobalExceptionHandler.java src/test/java/com/ssen/voca/auth/AuthControllerTest.java
git commit -m "feat: 회원가입·로그인·재발급 API 엔드포인트 추가 (#5)"
```

---

### Task 6: JWT 인증 필터 + 내 정보/참여코드 API

**Files:**
- Create: `src/main/java/com/ssen/voca/auth/JwtAuthenticationFilter.java`
- Modify: `src/main/java/com/ssen/voca/auth/SecurityConfig.java`
- Create: `src/main/java/com/ssen/voca/user/dto/UserResponse.java`
- Create: `src/main/java/com/ssen/voca/user/dto/ClassCodeRequest.java`
- Create: `src/main/java/com/ssen/voca/user/UserController.java`
- Test: `src/test/java/com/ssen/voca/user/UserControllerTest.java`

**Interfaces:**
- Consumes: `JwtService`(Task 3), `AppUserRepository`(Task 2), `AuthController`(Task 5, 테스트에서 토큰 발급용으로 호출).
- Produces: `GET /api/users/me`(200/401), `PATCH /api/users/me/class-code`(200/401) — 이 태스크로 스펙의 "가입→로그인→인증 접근" happy path가 완성된다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.ssen.voca.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.user.dto.ClassCodeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String signupAndGetAccessToken(String email) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new SignupRequest("김학생", email, "password123"))))
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
	}

	@Test
	void meWithoutTokenReturns401() throws Exception {
		mockMvc.perform(get("/api/users/me"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void meWithValidTokenReturns200WithUserInfo() throws Exception {
		String accessToken = signupAndGetAccessToken("me@example.com");

		mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("me@example.com"))
				.andExpect(jsonPath("$.name").value("김학생"));
	}

	@Test
	void meWithInvalidTokenReturns401() throws Exception {
		mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer not-a-real-token"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void updateClassCodeThenMeReflectsIt() throws Exception {
		String accessToken = signupAndGetAccessToken("classcode@example.com");

		mockMvc.perform(patch("/api/users/me/class-code")
						.header("Authorization", "Bearer " + accessToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ClassCodeRequest("WINTER-2026-A"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.classCode").value("WINTER-2026-A"));

		mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(jsonPath("$.classCode").value("WINTER-2026-A"));
	}
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.ssen.voca.user.UserControllerTest"`
Expected: **FAIL** — Security 필터 체인이 서블릿 라우팅보다 먼저 실행되므로, 아직 `JwtAuthenticationFilter`가 없어 모든 요청이 익명(anonymous)으로 처리되고 `anyRequest().authenticated()`에 걸려 기본적으로 403이 반환된다(테스트가 기대하는 401/200과 다름).

- [ ] **Step 3: JwtAuthenticationFilter 구현**

```java
package com.ssen.voca.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtService jwtService;

	public JwtAuthenticationFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");

		if (header != null && header.startsWith("Bearer ")) {
			try {
				String userId = jwtService.parseAccessToken(header.substring(7)).getSubject();
				SecurityContextHolder.getContext().setAuthentication(
						new UsernamePasswordAuthenticationToken(userId, null, List.of()));
			} catch (InvalidTokenException ignored) {
				SecurityContextHolder.clearContext();
			}
		}

		filterChain.doFilter(request, response);
	}
}
```

- [ ] **Step 4: SecurityConfig 수정 (필터 등록 + 401 응답 명시)**

`SecurityConfig.java` 전체를 아래 내용으로 교체한다(Task 1에서 만든 버전 대체):

```java
package com.ssen.voca.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

	private final JwtService jwtService;

	public SecurityConfig(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/health", "/api/auth/**").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(exception -> exception
						.authenticationEntryPoint((request, response, authException) ->
								response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
				.addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}
}
```

- [ ] **Step 5: UserController + DTO 구현**

```java
package com.ssen.voca.user.dto;

public record UserResponse(Long id, String name, String email, String classCode, String role) {
}
```

```java
package com.ssen.voca.user.dto;

import jakarta.validation.constraints.NotBlank;

public record ClassCodeRequest(@NotBlank String classCode) {
}
```

```java
package com.ssen.voca.user;

import com.ssen.voca.user.dto.ClassCodeRequest;
import com.ssen.voca.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
public class UserController {

	private final AppUserRepository appUserRepository;

	public UserController(AppUserRepository appUserRepository) {
		this.appUserRepository = appUserRepository;
	}

	@GetMapping
	public UserResponse me(Authentication authentication) {
		AppUser user = findCurrentUser(authentication);
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getClassCode(), user.getRole());
	}

	@PatchMapping("/class-code")
	public UserResponse updateClassCode(Authentication authentication, @Valid @RequestBody ClassCodeRequest request) {
		AppUser user = findCurrentUser(authentication);
		user.updateClassCode(request.classCode());
		appUserRepository.save(user);
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getClassCode(), user.getRole());
	}

	private AppUser findCurrentUser(Authentication authentication) {
		Long userId = Long.valueOf(authentication.getName());
		return appUserRepository.findById(userId).orElseThrow();
	}
}
```

- [ ] **Step 6: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.ssen.voca.user.UserControllerTest"`
Expected: **PASS**

- [ ] **Step 7: 전체 테스트 스위트 실행**

Run: `./gradlew build`
Expected: **PASS** (모든 태스크의 테스트 포함)

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/ssen/voca/auth/JwtAuthenticationFilter.java src/main/java/com/ssen/voca/auth/SecurityConfig.java src/main/java/com/ssen/voca/user/dto src/main/java/com/ssen/voca/user/UserController.java src/test/java/com/ssen/voca/user/UserControllerTest.java
git commit -m "feat: JWT 인증 필터 및 내 정보·참여코드 API 추가 (#5)"
```

---

## 완료 후

이슈 #5의 "완료 조건"(회원가입→로그인→`/api/users/me` 인증 접근, `./gradlew build` 통과)이 모두 충족된다. `develop`으로 PR을 올린다.
