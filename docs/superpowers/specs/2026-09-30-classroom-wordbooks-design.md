# 수업 · 교사 단어장 · 나만의 단어장 설계

## 배경

팀 논의(2026-09-30)로 아래 네 가지가 확정됐다. 이후 교사 계정 방식과 교사 가입 코드가 추가로 정해졌다.

1. 학생 로그인은 **참여코드 + 이름 + 휴대폰 뒤 4자리**다.
2. 교사 단어장은 **서버에 올려 두고**, 학생은 참여코드로 자기 수업의 단어장을 본다. **올리는 방식(CSV 여부 포함)은 교사 회의 후 확정한다.** 이 문서의 CSV 관련 내용은 모두 **잠정안**이다.
3. **나만의 단어장**은 학생 개인이 암기하지 못한 단어를 버튼으로 담는 개인 목록이며 서버에 저장한다.
4. 웹은 **Vercel**에 배포한다.
5. 교사는 **교사 계정**(이메일 + 비밀번호)으로 로그인해 수업을 만들고 CSV를 올린다. 교사 가입은 **교사 가입 코드**를 아는 사람만 할 수 있다.

현재 상태(PR 리뷰 중): 학생은 이름 + 휴대폰 뒤 4자리로 가입·로그인하고(ssen-server #8), 참여코드는 검사 없이 문자열로만 저장한다(`app_user.class_code`). 단어 데이터는 앱 브라우저의 로컬 저장소에만 있다. 이 문서는 두 가지를 바꾼다: 참여코드가 실제 **수업**을 가리키게 하고, 단어장이 **서버에서 내려오게** 한다.

## 조각과 순서

한 번에 만들기엔 크므로 조각으로 나누고, 조각마다 이슈 → (필요하면 스펙) → 구현 계획 → PR로 진행한다. **이 문서는 조각 1을 상세히, 나머지는 방향과 경계만** 적는다. 조각 2 이후는 해당 조각을 시작할 때 이 문서를 기준으로 상세화한다.

| # | 조각 | 저장소 | 선행 |
|---|---|---|---|
| 1a | 교사 계정 · 수업 생성(참여코드 발급) API | 서버 | ssen-server #8 머지 |
| 1b | 교사 단어장 업로드 API | 서버 | **교사 회의 후 확정**, 1a |
| 2 | 학생 로그인을 참여코드 + 이름 + 뒤 4자리로 변경, `/api/users/me`에 소속 수업 | 서버 + 앱(로그인 화면) | 1a |
| 3 | 앱이 로그인한 학생에게 서버 단어장을 보여줌 (학생용 단어장 조회 API `GET /api/classroom/words` 포함, 단어 테이블이 생기는 1b가 필요) | 서버 + 앱 | 2, 1b(개발 중에는 DB에 넣은 임시 단어로 대체 가능) |
| 4 | 나만의 단어장 | 서버 + 앱 | 3 |
| 5 | 교사용 화면(교사 로그인, 수업 목록·생성, 단어 업로드) | 앱(웹) | 1a, 업로드 부분은 1b |
| 6 | Vercel 배포 + 서버 호스팅 | 양쪽 | 조각 3 이상. `vercel.json` 등 배포 설정은 먼저 해 둘 수 있다 |

**교사 회의 전에 진행할 수 있는 것**: 1a, 2, 그리고 3·4의 앱 쪽(단어는 DB에 직접 넣은 임시 데이터나 테스트 데이터로 확인), 6의 배포 설정. **회의 결과를 기다리는 것**은 1b와 조각 5의 업로드 화면뿐이다. 조각 5의 나머지(교사 로그인, 수업 목록·생성)는 1a와 함께 진행할 수 있다. 챕터 관리, 퀴즈, Gemini 자동 채우기는 이 계획 밖이며 이후로 미룬다.

## 용어

- **수업(classroom)**: 교사가 만든 단어장의 단위이자 학생이 속하는 단위. 참여코드가 수업을 식별한다.
- **참여코드**: 수업마다 서버가 발급하는 6자리 코드.
- **교사 단어장**: 수업에 속한 단어 목록. CSV 업로드로 만든다.

## 조각 1 — 교사 계정 · 수업 · 단어 업로드 (상세)

**1a(교사 계정 · 수업 · 참여코드)는 확정이고, 1b(단어 업로드)는 잠정안이다.** 아래에서 `classroom_word` 테이블, 업로드 API 두 줄, `CSV 형식과 업로드 규칙` 절, 그에 해당하는 CSV 테스트는 **교사 회의 전까지 구현하지 않는다**(1b).

### 데이터 모델 (Flyway V4: 1a는 `app_user` 변경과 `classroom`, `classroom_word`는 1b)

`app_user` 한 테이블에 `role`(STUDENT/TEACHER)을 그대로 쓴다. 교사는 이메일 + 비밀번호, 학생은 이름 + 뒤 4자리다. 토큰 구조를 바꾸지 않고 role만 더하기 때문에 변경이 가장 작다.

```sql
-- 비밀번호(교사)와 PIN(학생)을 같은 열에 담으므로 이름을 일반화한다
ALTER TABLE app_user RENAME COLUMN pin_hash TO secret_hash;
ALTER TABLE app_user ADD COLUMN email VARCHAR(255);
CREATE UNIQUE INDEX uq_app_user_email ON app_user (email) WHERE email IS NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_teacher_email
    CHECK (role <> 'TEACHER' OR email IS NOT NULL);

CREATE TABLE classroom (
    id         BIGSERIAL PRIMARY KEY,
    teacher_id BIGINT       NOT NULL REFERENCES app_user (id),
    name       VARCHAR(100) NOT NULL,
    code       VARCHAR(6)   NOT NULL UNIQUE,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_classroom_teacher ON classroom (teacher_id);

CREATE TABLE classroom_word (
    id               BIGSERIAL PRIMARY KEY,
    classroom_id     BIGINT       NOT NULL REFERENCES classroom (id) ON DELETE CASCADE,
    position         INT          NOT NULL,
    word             VARCHAR(100) NOT NULL,
    word_key         VARCHAR(100) NOT NULL,      -- 소문자·공백 정규화, 중복 판별용
    chapter          VARCHAR(50)  NOT NULL DEFAULT 'Day 1',
    meanings         JSONB        NOT NULL,      -- 문자열 배열
    definitions      JSONB        NOT NULL,      -- meanings와 같은 길이, 빈 문자열 허용
    example          TEXT         NOT NULL DEFAULT '',
    example_ko       TEXT         NOT NULL DEFAULT '',
    example_meaning  TEXT         NOT NULL DEFAULT '',
    synonyms         TEXT         NOT NULL DEFAULT '',
    antonyms         TEXT         NOT NULL DEFAULT '',
    derived          TEXT         NOT NULL DEFAULT '',
    related          TEXT         NOT NULL DEFAULT '',
    CONSTRAINT uq_classroom_word UNIQUE (classroom_id, word_key, chapter)
);
```

- 필드 구성은 앱의 `Word`·`WordDetails` 타입과 같다(`definitions`는 `meanings`와 길이가 같다).
- **참여코드**: `ABCDEFGHJKMNPQRSTUVWXYZ23456789`(0/O, 1/I/L 제외) 6자리, `SecureRandom`으로 생성한다. 유일 제약 위반이면 최대 5번 다시 뽑는다. 입력은 앞뒤 공백 제거 후 대문자로 바꿔 비교한다.
- **단어 식별**: `(수업, word_key, 챕터)`가 자연 키다. 업로드가 이 키로 기존 행을 갱신하므로 **같은 단어는 재업로드해도 id가 유지된다**. 나만의 단어장(조각 4)이 이 id를 참조할 수 있게 하기 위한 결정이다.
- 기존 `app_user.class_code` 열은 조각 2에서 제거한다(이 조각에서는 건드리지 않는다).

### 인증

- 액세스 토큰에 `role` claim을 추가한다. 토큰을 만들 때(로그인·리프레시) DB에서 읽은 role을 넣는다.
- `JwtAuthenticationFilter`가 role로 권한(`ROLE_TEACHER`, `ROLE_STUDENT`)을 만든다. 지금은 권한이 비어 있다.
- `SecurityConfig`: `/api/teacher/signup`, `/api/teacher/login`은 공개. 그 외 `/api/teacher/**`는 `TEACHER`만. 나머지는 지금과 같다.
- **교사 가입 코드**: 환경변수 `TEACHER_INVITE_CODE`(운영은 필수, local 프로필은 기본값 있음). 상수 시간 비교한다. 잘못된 코드 입력은 `teacher-signup` 키 하나로 시도 제한에 합산한다(5회/5분). 이 때문에 누가 잠그면 모든 교사 가입이 5분간 막히지만, 가입은 드문 일이라 감수한다.
- 교사 로그인 시도 제한은 기존 `LoginAttemptLimiter`를 재사용하고, 키는 `teacher:<이메일>`이다. 선차감·성공 시 1회 환불 규칙도 그대로다.
- 교사 이메일은 소문자로 저장·비교한다. 비밀번호는 8자 이상, BCrypt.
- 리프레시(`POST /api/auth/refresh`)는 교사·학생 공통이다.

### API

| 메서드 · 경로 | 설명 | 성공 | 주요 오류 |
|---|---|---|---|
| `POST /api/teacher/signup` `{name, email, password, inviteCode}` | 교사 가입 | 토큰 | 400 검증, 403 가입 코드 불일치, 409 이메일 중복, 429 |
| `POST /api/teacher/login` `{email, password}` | 교사 로그인 | 토큰 | 401, 429 |
| `POST /api/teacher/classrooms` `{name}` | 수업 생성, 참여코드 발급 | 201 `{id, name, code, createdAt}` | 400 |
| `GET /api/teacher/classrooms` | 내 수업 목록(최신순, wordCount는 1b에서 추가) | 200 | |
| (1b, 잠정) `PUT /api/teacher/classrooms/{id}/words` multipart `file` | CSV로 단어장 교체 | 200 `{added, updated, removed, total}` | 400 + `errors`, 404, 413 |
| (1b) `GET /api/teacher/classrooms/{id}/words` | 수업 단어 목록(`position` 순) | 200 | 404 |

- **교사는 자기 수업만** 접근한다. 남의 수업 id는 존재 여부를 드러내지 않도록 404를 돌려준다.
- 403 가입 코드 불일치만 예외적으로 403이다(인증 실패와 구분). 나머지 오류 형식은 기존 `{message}`다.
- CSV 오류 응답은 `{message, errors: [{row, message}]}`로 `errors`를 더한다.
- `GET /api/users/me`는 교사일 때 `email`도 돌려준다(학생은 `null`).

### CSV 형식과 업로드 규칙 (1b, 잠정 — 교사 회의 후 확정)

이 절은 회의에서 방식이 바뀔 수 있다(예: 구글 시트·엑셀 직접 업로드, 웹에서 직접 입력, 덧붙이기 방식). 열 구성, 교체 의미, 오류 처리, 크기 제한 모두 회의 결과에 따라 고친다. 아래 "회의에서 확인할 것" 참고.

- **인코딩**: UTF-8(BOM 허용)로 엄격하게 읽어 보고, 실패하면 CP949(`x-windows-949`)로 다시 읽는다. 한글 엑셀의 기본 CSV 저장이 CP949라서 필요하다.
- **구분자**는 쉼표, 따옴표 안의 줄바꿈을 지원한다. 파서는 `commons-csv`를 쓴다(RFC 4180 따옴표 처리를 직접 만들지 않는다).
- **첫 줄은 제목 행**이다. 제목은 앞뒤 공백·대소문자를 무시하고 아래 별칭을 받는다. 알 수 없는 열은 무시한다.

| 필드 | 별칭 | 필수 |
|---|---|---|
| word | 영어, english, word | O |
| meanings | 뜻, meaning, meanings | O |
| chapter | 챕터, chapter | |
| definitions | 영영풀이, definition, definitions | |
| example | 예문, example | |
| exampleKo | 한국어예문, 예문해석, exampleKo | |
| exampleMeaning | 사용된뜻, exampleMeaning | |
| synonyms / antonyms / derived / related | 동의어 / 반의어 / 파생어 / 유의어 | |

- 뜻은 줄바꿈 또는 `;`로 나눈다(앱의 폼과 같은 규칙). 영영풀이는 줄바꿈으로 나눠 뜻 순서대로 맞추고, 모자라면 빈 문자열로 채운다.
- 행 규칙: 빈 행은 건너뛴다. 영어 ≤ 100자, 뜻 각 ≤ 200자, 챕터 ≤ 50자(없으면 `Day 1`), 나머지 텍스트 ≤ 1000자. **예문 쌍 검사와 사용된 뜻 포함 검사는 하지 않는다**(앱 폼의 검증과 달리 업로드는 관대하게 받는다).
- 같은 파일 안에서 `(word_key, 챕터)`가 겹치면 뒤쪽 행을 오류로 본다.
- 파일 크기 ≤ 2MB, 데이터 행 ≤ 5000.
- **전부 아니면 전무**: 오류가 하나라도 있으면 저장하지 않고 **최대 50개**의 `{row, message}`를 돌려준다. `row`는 스프레드시트에서 보이는 행 번호(제목 행이 1)다.
- **교체 의미**: 한 트랜잭션에서 `(word_key, 챕터)`로 기존 행과 맞춰 갱신, 없으면 추가, 파일에 없는 행은 삭제한다. `position`은 파일 순서로 다시 매긴다. 같은 파일을 다시 올리면 `added=0, updated=n, removed=0`이다.

### 테스트

`@SpringBootTest` + MockMvc + 실제 Postgres(기존 방식)로 다음을 본다.

- 교사 가입·로그인: 성공, 가입 코드 불일치 403과 시도 제한, 이메일 중복 409, 검증 400, 잘못된 비밀번호 401, 이메일 대소문자 무시
- 권한: 학생 토큰으로 `/api/teacher/**` → 403, 토큰 없음 → 401, 남의 수업 → 404, 교사가 학생 전용 경로를 쓸 수 없음
- 수업: 생성 시 6자리 코드·문자 집합, 코드 충돌 재시도(생성기를 주입해 테스트), 목록은 내 수업만
- CSV: 별칭 제목, 필수 열 누락, BOM, CP949 인코딩 파일, 따옴표 안 줄바꿈, 뜻 분리, 영영풀이 정렬, 오류 50개 상한과 행 번호, 중복 행, 크기·행 수 초과, 재업로드 시 id 유지·added/updated/removed 계산, 오류 시 기존 단어 불변
- 마이그레이션: V3 → V4가 기존 행(학생)을 깨지 않고, 교사 행에 이메일이 없으면 제약에 걸림

## 조각 2 — 학생 로그인 변경 (방향)

- 요청에 `classCode`가 추가된다. 참여코드는 앞뒤 공백 제거 + 대문자화 후 31자 집합의 6자리가 아니면 "없는 코드"다.
- 학생은 **수업 안에서** `(이름, PIN)`으로 식별한다(`app_user.classroom_id`). 시도 제한 키는 `student:<수업 id>:<정규화한 이름>`이다.
- 없는 코드: 가입은 400 `참여 코드를 확인해 주세요.`, 로그인은 일반 401이며 두 경우 모두 시도 제한 카운터를 만들지 않는다. 코드 공간(약 8.9억)이 넓어 대입이 현실적이지 않지만 코드 조회 자체에는 IP별 제한을 넣지 않았다.
- 알려진·수용한 한계: 참여코드의 존재 여부는 두 가지로 추측할 수 있다. (1) 가입: 없는 코드는 400 `참여 코드를 확인해 주세요.`, 실제 코드는 201/409/429로 답하므로 바로 드러난다(학생이 코드가 틀렸음을 알아야 하므로 의도된 설계이고 제한하지 않는다). (2) 로그인: 메시지는 항상 같은 401이지만 실제 코드로 틀린 시도를 반복하면 결국 429가 나오고 없는 코드는 429가 나오지 않는다. 코드는 31^6가지이고 코드 조회 자체는 제한하지 않는다. 참고로 20자를 넘는 코드는 로그인에서도 일반 401이 아니라 빈 값·길이 검증의 400이지만, 그런 문자열은 유효한 코드가 될 수 없으므로 새는 정보는 없다.
- `class_code` 열과 `PATCH /api/users/me/class-code`는 제거하고, `GET /api/users/me`는 `classroom: {id, name, code}`(없으면 null)를 돌려준다. 수업이 없던 기존 학생 행은 로그인할 수 없지만 `/me`는 깨지지 않는다.
- **`GET /api/classroom/words`는 단어 테이블이 생기는 1b 이후(조각 3)로 미룬다.**
- 앱의 참여 코드 입력 화면은 로그인 폼에 흡수되어 사라진다(앱 저장소 별도 이슈).

## 조각 3 — 앱이 서버 단어장을 보여줌 (방향)

- 로그인한 학생: 홈·학습 카드가 서버 단어장(`GET /api/classroom/words`)을 보여준다. 앱을 열 때 받아 AsyncStorage에 캐시해 두고, 네트워크가 없으면 캐시를 쓴다.
- 로그인하지 않은 사용자: 지금처럼 샘플 + 로컬 단어(추가·수정·삭제 가능)를 쓴다.
- **기본안**: 로그인한 학생에게는 교사 단어장이 읽기 전용이므로 단어 추가·수정·삭제 버튼을 숨기고, 로컬 단어는 섞지 않는다. 이 동작은 조각 3을 시작할 때 확정한다(아래 열린 질문 1).

## 조각 4 — 나만의 단어장 (방향)

- `saved_word`: 학생별 저장 단어. 저장 시점의 단어를 **스냅샷으로 복사**해 교사가 단어를 지우거나 고쳐도 남는다. 원본 단어 id는 참고용(nullable)으로 같이 둔다. 같은 학생이 같은 단어를 두 번 담지 못하게 `(학생, word_key, 챕터)`를 유일하게 한다.
- API: `POST /api/me/saved-words {wordId}`, `GET /api/me/saved-words`, `DELETE /api/me/saved-words/{id}`(외웠으면 뺀다).
- 앱: 홈 단어 카드와 학습 카드에 "나만의 단어장에 저장" 버튼, 나만의 단어장 화면(기존 카드·학습 화면을 재사용). 로그인하지 않으면 버튼이 로그인 안내로 이어진다.

## 조각 5 — 교사용 화면 (방향)

- 내 정보의 로그인 화면에 "교사로 로그인" 경로를 둔다(교사 가입은 가입 코드 입력 칸 포함).
- 수업 목록·생성, 참여코드 표시와 복사, CSV 파일 선택·업로드, 결과(추가·갱신·삭제 개수 또는 행 번호별 오류 목록), 제목 행이 들어 있는 CSV 양식 내려받기.
- 파일 선택은 웹 `<input type="file">`이며, 네이티브 전환 때 `expo-document-picker`로 바꾼다.

## 조각 6 — 배포 (방향)

- Vercel: 빌드 `npm run build:web`, 출력 `dist`, 모든 경로를 `index.html`로 보내는 `vercel.json` rewrite, 프로젝트 환경변수 `EXPO_PUBLIC_API_BASE_URL`(빌드 시점에 코드에 박힘).
- 서버는 https 호스팅이 필요하다(https 페이지는 http API를 호출할 수 없다). 호스팅 업체·DB 백업·비용은 이 조각을 시작할 때 정한다(열린 질문 2).
- 서버 `CORS_ALLOWED_ORIGINS`에 운영 주소만 넣는다(미리보기 주소는 허용하지 않는다). 운영 필수 환경변수: `JWT_SECRET`, `TEACHER_INVITE_CODE`, DB 접속 정보.

## 이 계획의 범위 밖

교사 비밀번호 재설정(분실 시 운영자가 DB에서 처리), 교사 계정 삭제·이름 변경, 수업 삭제·코드 재발급, 학생 탈퇴, 수업 종료, 교사의 학생 목록·학습 현황 조회, 챕터 관리, 퀴즈, Gemini 자동 채우기, 네이티브 앱 빌드.

## 교사 회의에서 확인할 것 (1b 확정용)

1. 단어 목록을 **어떤 프로그램으로 만들고 계신지**(엑셀, 한글, 구글 시트, 워드, 교재 PDF 등). 그 파일을 그대로 올릴 수 있어야 하는가.
2. 이미 가진 단어 목록의 **열 구성**: 영어, 뜻 외에 영영풀이, 예문(영·한), 동의어·반의어·파생어가 있는가. 챕터(Day)로 나누는가. 교재 사진이나 발음은 필요한가.
3. **올리는 사람**: 교사 본인인가, 조교가 대신 하는가. 수업(반)은 교사 한 명이 몇 개 만드는가.
4. **수정 방식**: 전체를 다시 올려 교체하는가, 새 단어만 덧붙이는가, 웹에서 단어 하나씩 고치는가. 수업 중에 단어가 바뀌는가.
5. **규모**: 수업당 단어 수(수백? 수천?)와 수업 수.
6. 틀린 데이터가 있을 때 **일부만 올라가도 되는가**, 전부 거부하고 오류 행을 알려 주는 편이 나은가.
7. 교사에게 **학생 학습 현황**(누가 무엇을 저장했는지)을 보여줘야 하는가. 이 계획에서는 범위 밖이다.

## 열린 질문

0. **교사 단어 업로드 방식**: 위 "교사 회의에서 확인할 것"으로 회의 후 확정.
1. **로그인한 학생의 로컬 단어 추가**: 조각 3의 기본안은 숨김이다. 학생이 자기 단어를 직접 넣는 기능을 남겨야 하는지 팀에서 정해야 한다. 나만의 단어장이 그 용도를 대신할 수 있다.
2. **서버 호스팅**: 업체, 비용, DB 백업, 학생에게 열어 둘 기간.
3. **개인정보**: 휴대폰 뒤 4자리를 받는 것에 대한 안내 문구·동의가 필요한지(고등학생 대상, 학원 기준) 확인이 필요하다.
4. **교사 가입 코드 전달 방식**: 누가 어떻게 교사에게 전달하고 언제 바꾸는지.

## 알려진 약점

- 학생 인증은 저엔트로피(뒤 4자리)라서 약하다. 수업 범위로 좁아지고 이름별 시도 제한이 있지만, 같은 수업 학생의 이름을 아는 사람이 5분에 5번씩 대입할 수 있다.
- 시도 제한은 단일 인스턴스 메모리 방식이다. 서버를 여러 대로 늘릴 때는 Redis나 DB로 옮겨야 한다.
- 교사 가입 코드가 유출되면 누구나 교사 계정을 만들 수 있다(자기 수업만 다룰 수 있고 다른 교사의 수업에는 접근할 수 없다).
