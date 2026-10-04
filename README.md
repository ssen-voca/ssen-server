# ssen-server

쎈슬기 단어장 — API 서버 (Spring Boot + PostgreSQL)

차시별 단어장, 학습 기록, 복습 스케줄, 조교용 엑셀 업로드를 담당하는 백엔드입니다.

## 기술 스택

| 영역 | 선택 |
| --- | --- |
| 프레임워크 | Spring Boot 3 (Java 21) |
| DB | PostgreSQL 16 |
| ORM | Spring Data JPA |
| 마이그레이션 | Flyway |
| 인증 | JWT (Access + Refresh) |
| 엑셀 파싱 | Apache POI |

## 주요 도메인

| 테이블 | 설명 |
| --- | --- |
| `lesson` | 차시 (수업 회차) |
| `word` / `sense` | 표제어와 의미 |
| `sense_relation` | 동의어 · 반의어 · 유의어 |
| `lesson_word` | 차시별 단어장 — 조교 엑셀 업로드의 결과 |
| `app_user` | 사용자 |
| `bookmark` | 나만의 단어장 |
| `study_record` | 학습 이력 + 복습 스케줄 |

## 로컬 실행

```bash
# PostgreSQL 실행 후
./gradlew bootRun
```

`src/main/resources/application-local.yml` 에 로컬 DB 접속 정보를 둡니다. (이 파일은 git에 올라가지 않습니다)

## 인증 API

학생은 **참여코드 + 이름 + 휴대폰 번호 뒤 4자리**로 가입·로그인합니다. 같은 이름·같은 번호의 학생도 수업이 다르면 각자 계정입니다. (이메일·비밀번호 없음)
휴대폰 뒤 4자리는 경우의 수가 1만 개뿐인 약한 자격 증명이므로 서버가 시도 횟수를 제한합니다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/auth/signup` | 가입 `{classCode, name, phoneLast4}` → 201 `{accessToken, refreshToken}`. 같은 수업의 같은 이름 + 같은 번호는 409, 없는 참여코드는 400 |
| POST | `/api/auth/login` | 로그인 `{classCode, name, phoneLast4}` → 200 `{accessToken, refreshToken}`. 불일치는 401 |
| POST | `/api/auth/refresh` | `{refreshToken}` → `{accessToken}` |
| GET | `/api/users/me` | 내 정보 `{id, name, role, email, classroom}` (액세스 토큰 필요). `classroom`은 `{id, name, code}`이고 교사 등 수업이 없으면 null |

- 이름은 앞뒤 공백 제거 · 연속 공백 1칸 · 소문자로 정규화해 같은 학생인지 판단합니다. 같은 수업 안에서 같은 이름의 다른 학생은 번호로 구분합니다.
- 참여코드는 앞뒤 공백 제거 + 대문자화 후 판단합니다. 없는 참여코드는 가입에서 400, 로그인에서 일반 401이며 시도 제한을 소모하지 않습니다.
- 번호는 BCrypt 해시로만 저장하며 응답·로그에 남기지 않습니다.
- 시도 제한: 수업 + 이름 기준(키 `student:<수업 id>:<이름>`)으로 가입 호출과 로그인 시도를 합쳐 5분 안에 5회가 되면 다음 시도는 429입니다. 시도는 처리 전에 먼저 차감되고, 로그인에 성공하면 그 1회만 돌려받습니다(카운터를 통째로 비우지 않습니다). (서버 1대 기준 메모리 방식 — `app.auth.max-attempts`, `app.auth.attempt-window-millis`)
- 앱이 직접 내는 오류(가입 중복 409, 로그인 실패 401(`참여 코드, 이름 또는 휴대폰 번호가 올바르지 않아요.`), 시도 초과 429, 검증 실패·본문 형식 오류 400)는 `{"message": "..."}` 형태이고, 검증 실패는 첫 번째 오류 메시지를 담습니다. 프레임워크가 내는 오류(CORS 거절 403 "Invalid CORS request", 토큰 없는 요청의 401, 405·415 등)는 Spring 기본 응답 본문을 씁니다.
- 알려진 한계: 참여코드의 존재 여부는 두 가지로 추측할 수 있습니다. (1) 가입: 없는 코드는 400 `참여 코드를 확인해 주세요.`, 실제 코드는 201/409/429로 답하므로 바로 드러납니다(학생이 코드가 틀렸음을 알아야 하므로 의도된 설계이며 제한하지 않습니다). (2) 로그인: 메시지는 항상 같은 401이지만 실제 코드로 틀린 시도를 반복하면 결국 429가 나오고 없는 코드는 429가 나오지 않습니다. 참여코드는 31^6가지(약 8.9억)이고 코드 조회 자체에는 시도 제한을 두지 않습니다. 20자를 넘는 코드는 로그인에서도 일반 401이 아니라 길이 검증의 400이지만, 그런 문자열은 유효한 코드가 될 수 없으므로 새는 정보는 없습니다.
- 이름은 눈에 보이지 않는 문자(폭 없는 공백 등)를 제거한 뒤 비어 있으면 400입니다.
- 액세스 토큰 30분 · 리프레시 토큰 14일 (JWT, 무상태).

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

### CORS

`app.cors.allowed-origins`(쉼표 구분)에 등록한 출처만 `/api/**`를 브라우저에서 호출할 수 있습니다.
`local` 프로필은 `http://localhost:8081`, `:8099`, `:19006`을 허용하고, 그 외에는 기본적으로 아무것도 허용하지 않습니다.
운영에서는 환경 변수 `CORS_ALLOWED_ORIGINS`로 지정합니다. 허용되지 않은 출처의 요청(preflight 포함)은 403으로 거절됩니다.

## 앱

클라이언트는 별도 저장소에 있습니다 → [ssen-app](https://github.com/ssen-voca/ssen-app)

## 기여 방법

브랜치·커밋·PR 규칙은 [CONTRIBUTING.md](./CONTRIBUTING.md)를 반드시 읽고 따라주세요.
`main`, `develop`에는 직접 push할 수 없으며 모든 작업은 이슈 → 브랜치 → PR 순서로 진행합니다.
