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

학생은 **학교 + 이름 + 휴대폰 번호 뒤 4자리**로 가입·로그인합니다. 학교가 다르면 같은 이름·같은 번호의 학생도 각자 계정입니다. (이메일·비밀번호 없음. 학생은 가입할 때 수업에 속하지 않고, 수업 배정은 이후 작업에서 정합니다.)
휴대폰 뒤 4자리는 경우의 수가 1만 개뿐인 약한 자격 증명이므로 서버가 시도 횟수를 제한합니다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/auth/signup` | 가입 `{school, name, phoneLast4}` → 201 `{accessToken, refreshToken}`. 같은 학교의 같은 이름 + 같은 번호는 409 |
| POST | `/api/auth/login` | 로그인 `{school, name, phoneLast4}` → 200 `{accessToken, refreshToken}`. 불일치는 401 |
| POST | `/api/auth/refresh` | `{refreshToken}` → `{accessToken}` |
| GET | `/api/users/me` | 내 정보 `{id, name, role, email, classroom}` (액세스 토큰 필요). `classroom`은 `{id, name, code}`이고 교사 등 수업이 없으면 null |

- 이름은 앞뒤 공백 제거 · 연속 공백 1칸 · 소문자로 정규화해 같은 학생인지 판단합니다. 같은 학교 안에서 같은 이름의 다른 학생은 번호로 구분합니다.
- 학교는 자유 입력이며 보이지 않는 문자 제거 · 한글 조합 방식 통일 · **모든 공백 제거** · 소문자로 정규화해 같은 학교인지 판단합니다(`동국대 부속고`와 `동국대부속고`는 같은 학교). 비었거나 100자를 넘으면 400이며 시도 제한을 쓰지 않습니다. 없는 학교로 로그인해도 일반 401이고 (학교, 이름)마다 시도 제한이 걸립니다.
- 번호는 BCrypt 해시로만 저장하며 응답·로그에 남기지 않습니다.
- 시도 제한: 수업 + 이름 기준(키 `student:<수업 id>:<이름>`)으로 가입 호출과 로그인 시도를 합쳐 5분 안에 5회가 되면 다음 시도는 429입니다. 시도는 처리 전에 먼저 차감되고, 로그인에 성공하면 그 1회만 돌려받습니다(카운터를 통째로 비우지 않습니다). (서버 1대 기준 메모리 방식 — `app.auth.max-attempts`, `app.auth.attempt-window-millis`)
- 앱이 직접 내는 오류(가입 중복 409, 로그인 실패 401(`학교, 이름 또는 휴대폰 번호가 올바르지 않아요.`), 시도 초과 429, 검증 실패·본문 형식 오류 400)는 `{"message": "..."}` 형태이고, 검증 실패는 첫 번째 오류 메시지를 담습니다. 프레임워크가 내는 오류(CORS 거절 403 "Invalid CORS request", 토큰 없는 요청의 401, 405·415 등)는 Spring 기본 응답 본문을 씁니다.
- 알려진 한계: 참여코드가 없어져 학교와 이름만 알면 4자리를 추측해 볼 수 있습니다. 지금은 (학교, 이름)마다 5분에 5회로만 막습니다(연속 실패 시 더 긴 잠금은 후속 작업). 학교 표기가 다르면(약칭 등) 다른 학교로 가입됩니다. 시도 제한 창은 키마다 메모리에 쌓이며 서버 한 대 기준입니다. 자세한 내용은 `docs/superpowers/specs/2026-10-30-school-identity-design.md`.
- 이름은 눈에 보이지 않는 문자(폭 없는 공백 등)를 제거한 뒤 비어 있으면 400입니다.
- 액세스 토큰 30분 · 리프레시 토큰 14일 (JWT, 무상태).

### 교사 API

교사 계정은 가입하지 않고 **서버 설정에 미리 등록**합니다(교사가 여러 명이어도 됩니다). 등록된 교사는 학생과 **같은 로그인**(`POST /api/auth/login` `{school, name, phoneLast4}`)을 쓰고, 토큰의 role이 `TEACHER`가 되어 교사 API를 쓸 수 있습니다(학생 토큰은 403). 별도의 교사 가입·로그인 API와 가입 코드는 없습니다([#21](https://github.com/ssen-voca/ssen-server/issues/21)).

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/teacher/classrooms` | 수업 생성 `{name}` → 201 `{id, name, code, createdAt}`. `code`는 6자리 참여코드 |
| GET | `/api/teacher/classrooms` | 내 수업 목록(최신순) |

#### 교사 등록 (`TEACHER_ACCOUNTS`)

환경 변수 `TEACHER_ACCOUNTS`(설정 `app.teacher.accounts`)에 `학교|이름|번호4자리`를 `;` 또는 줄바꿈으로 구분해 적습니다. 필드 앞뒤 공백은 무시합니다.

```bash
TEACHER_ACCOUNTS='데모학원|김선생|1234;데모학원|박선생|5678'
```

위 예시의 작은따옴표는 셸용입니다. **Railway Variables에는 따옴표 없이 값만** 붙여 넣으세요. 항목이 여러 개면 줄을 나눠 적거나 `;`로 구분합니다.

- 서버가 시작될 때 DB에 등록합니다. 같은 (학교, 이름)의 교사가 이미 있으면 그대로 두고, 번호가 달라졌으면 해시를 새로 씁니다(**설정이 기준**이므로 값을 바꾸고 다시 배포하면 번호가 교체됩니다).
- 형식이 틀리면(필드 수, 빈 학교·이름, 100/50자 초과, 번호가 숫자 4자리가 아님, 같은 학교·이름의 중복) 서버가 시작하지 않고, 오류에는 **몇 번째 항목인지**만 나오며 번호는 로그에 남기지 않습니다.
- 기본값은 비어 있습니다(교사 없음). `local` 프로필만 개발용 교사 `테스트학원|테스트교사|0000`이 기본으로 등록됩니다.
- 번호는 **자격 증명**입니다. 서버 환경 변수·시크릿에만 두고 저장소·메신저에 올리지 마세요. DB에는 BCrypt 해시만 저장합니다.
- 같은 학교·같은 이름으로 학생이 가입해도 교사 번호와 다르면 **별개의 학생 계정**이라 교사 권한을 얻지 못합니다. 교사 번호와 같은 번호로 가입하려 하면 409입니다. 로그인 때 교사 계정을 학생보다 먼저 맞춰 봅니다.
- 같은 (학교, 이름)의 학생이 같은 번호로 이미 가입해 있으면 그 학생이 교사가 되어 버리므로 서버가 시작하지 않습니다(오류에는 항목 번호만 나옵니다). 그 항목의 번호를 바꿔 주세요. 같은 (학교, 이름)의 교사 행이 여러 개 있으면 번호가 다른 행을 모두 설정 번호로 바꿉니다.
- 한계: 설정에서 항목을 지워도 **이미 등록된 계정은 회수되지 않습니다**(DB에서 직접 지우거나 번호를 바꿔 쓰지 못하게 해야 합니다). 교사 권한도 학생과 같은 4자리 번호 + (학교, 이름)당 5분 5회 제한으로만 지켜지므로, 교사 목록은 짧게 유지하고 번호는 추측하기 어렵게(생일·`1234` 같은 값 금지) 정하세요.
- 교사도 학생과 같은 (학교, 이름) 시도 제한 버킷을 씁니다. 그래서 누구든 그 교사의 (학교, 이름)으로 5번 틀리게 시도하면(가입 시도 포함) 그 교사는 5분간 로그인할 수 없고, 이를 반복할 수 있습니다.
- `TEACHER_ACCOUNTS`는 **NFC로 조합된 한글**로 입력하세요. 자모가 풀린(NFD) 한글을 붙여 넣으면 휴대폰 입력과 일치하지 않을 수 있습니다.
- 옛 이메일 교사 계정(`email` 열)은 DB에 남아 있고 `/api/users/me`의 `email`로만 보입니다. 새 로그인 방식으로는 로그인할 수 없습니다.
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

## 배포 (Railway)

`Dockerfile`과 `railway.json`이 있어서 Railway가 저장소를 그대로 빌드·실행합니다. 서버는 Railway가 넣어 주는 `PORT`로 뜨고 헬스체크는 `/api/health`입니다. DB 마이그레이션(Flyway)은 서버가 시작할 때 자동으로 적용됩니다.

1. Railway에서 **New Project → Deploy from GitHub repo**로 이 저장소를 연결하고 배포 브랜치를 `develop`으로 둡니다(이후 `develop`에 머지될 때마다 자동 배포).
2. 같은 프로젝트에 **PostgreSQL**을 추가합니다.
3. 서버 서비스의 **Variables**에 다음을 넣습니다.

| 변수 | 값 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DB_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
| `DB_USERNAME` | `${{Postgres.PGUSER}}` |
| `DB_PASSWORD` | `${{Postgres.PGPASSWORD}}` |
| `JWT_SECRET` | 긴 무작위 문자열(예: `openssl rand -base64 48`). 바꾸면 기존 로그인이 모두 풀립니다 |
| `TEACHER_ACCOUNTS` | 교사 계정 `학교\|이름\|번호4자리`(`;` 또는 줄바꿈 구분). 번호는 비밀이므로 Railway Variables에만 둡니다. 비워 두면 교사가 없습니다 |
| `CORS_ALLOWED_ORIGINS` | 앱 주소(예: `https://내앱.vercel.app`). 쉼표로 여러 개 가능, 슬래시로 끝내지 않습니다 |

4. **Settings → Networking → Generate Domain**으로 공개 주소를 만들고, 앱(Vercel)의 `EXPO_PUBLIC_API_BASE_URL`에 그 주소(`https://…up.railway.app`)를 넣습니다.

**주의:** `SPRING_PROFILES_ACTIVE=prod`를 꼭 지정하세요. 기본 프로필(`local`)로 뜨면 개발용 교사 `테스트학원|테스트교사|0000`이 운영 DB에 등록됩니다. 또 `TEACHER_ACCOUNTS`에서 항목을 지워도 이미 등록된 계정은 삭제되지 않습니다.

`JWT_SECRET`에는 기본값이 없어서 빠뜨리면 서버가 시작하지 않습니다. `TEACHER_ACCOUNTS`는 비어 있으면 교사가 없을 뿐이고, 형식이 틀리면 서버가 시작하지 않습니다. 시도 제한은 서버 메모리에 있어서 서비스를 한 개(replica 1)로만 돌려야 합니다.
