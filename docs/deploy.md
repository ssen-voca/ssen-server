# 운영 배포 가이드 (Railway Pro)

서버(Spring Boot)와 DB(PostgreSQL)를 Railway에 올리는 절차와, 올린 뒤 지켜야 할 점·백업·복원을 정리한 문서입니다. 앱 웹 화면은 별도 호스팅이며 [ssen-app](https://github.com/ssen-voca/ssen-app)에서 다룹니다. 이슈 #26.

`Dockerfile`과 `railway.json`이 저장소에 있어서 Railway가 이 저장소를 그대로 빌드·실행합니다. 서버는 Railway가 넣어 주는 `PORT`로 뜨고 헬스체크는 `/api/health`입니다. DB 스키마(Flyway)는 서버가 시작할 때 자동으로 적용됩니다.

## 0. 사람이 직접 해야 하는 일

아래는 계정·결제·비밀값이라 코드나 자동화로 할 수 없습니다.

- [ ] Railway 가입, **Pro 플랜** 결제(예약 백업은 Pro에서만 쓸 수 있고 Hobby에서는 쓸 수 없습니다)
- [ ] 교사 계정으로 쓸 학교·이름·번호 4자리 정하기(번호는 비밀입니다)
- [ ] `JWT_SECRET` 만들기: `openssl rand -base64 48`
- [ ] 앱 웹 화면의 주소 정하기(서버의 `CORS_ALLOWED_ORIGINS`에 넣어야 합니다)

## 1. Railway 설정

1. **New Project → Deploy from GitHub repo**로 이 저장소를 연결하고 배포 브랜치를 `develop`으로 둡니다. 이후 `develop`에 머지될 때마다 자동으로 배포됩니다. (`main`으로 두면 `main`에 머지할 때만 배포됩니다.)
2. 같은 프로젝트에 **PostgreSQL**을 추가합니다.
3. 서버 서비스의 **Variables**에 아래 값을 넣습니다. Variables에는 따옴표 없이 값만 붙여 넣습니다.

| 변수 | 값 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DB_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
| `DB_USERNAME` | `${{Postgres.PGUSER}}` |
| `DB_PASSWORD` | `${{Postgres.PGPASSWORD}}` |
| `JWT_SECRET` | 긴 무작위 문자열. 바꾸면 기존 로그인이 모두 풀립니다 |
| `TEACHER_ACCOUNTS` | `학교\|이름\|번호4자리`, 여러 명은 `;` 또는 줄바꿈으로 구분 |
| `CORS_ALLOWED_ORIGINS` | 앱 웹 주소(예: `https://내앱.example.com`). 쉼표로 여러 개 가능, 슬래시로 끝내지 않습니다 |

4. **Settings → Networking → Generate Domain**으로 공개 주소(`https://….up.railway.app`)를 만들고, 앱 웹 빌드의 `EXPO_PUBLIC_API_BASE_URL`에 그 주소를 넣어 다시 빌드합니다.
5. **서비스는 한 개(replica 1)만** 돌립니다. 로그인·참여코드 시도 제한이 서버 메모리에 있어서 여러 개로 늘리면 제한이 서로 공유되지 않습니다.

## 2. 첫 배포 확인

배포가 끝나면 아래를 순서대로 확인합니다. (`$SERVER`는 4번에서 만든 주소)

```bash
curl -s $SERVER/api/health          # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' -X POST $SERVER/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"school":"학교","name":"이름","phoneLast4":"0000"}'   # 등록한 교사 계정이면 200
curl -s -o /dev/null -w '%{http_code}\n' $SERVER/api/me/words  # 토큰 없이는 401
```

- 개발용 교사 `테스트학원|테스트교사|0000`으로 로그인해서 **401**이 나와야 합니다. 200이면 `prod` 프로필이 적용되지 않은 것이니 바로 `SPRING_PROFILES_ACTIVE`를 확인하세요.
- 앱 웹 주소에서 교사로 로그인해 수업을 만들고, 학생 계정으로 가입해 참여 코드로 들어가는 것까지 한 번 해 봅니다.
- 앱 웹 주소가 아닌 출처에서 보낸 요청은 CORS로 막혀야 합니다(`Access-Control-Allow-Origin`이 앱 주소에만 붙습니다).

## 3. 백업

### 3-1. 예약 백업 켜기 (Railway Pro)

Railway 대시보드에서 **Postgres 서비스 → Backups 탭**으로 가서 예약 백업(일간 등)을 켭니다. 볼륨 전체를 스냅샷으로 저장하며 Pro 플랜에서만 쓸 수 있습니다. 볼륨을 지우면 그 볼륨의 백업도 함께 지워지므로, 아래 3-2의 외부 덤프도 가끔 받아 둡니다.

### 3-2. 직접 덤프 받기

Postgres 서비스 **Variables**에서 외부 접속용 연결 주소를 복사해 `$DB_PUBLIC_URL`로 씁니다. (정확한 변수 이름과 외부 접속 설정은 대시보드에서 확인하세요.)

```bash
pg_dump -Fc "$DB_PUBLIC_URL" > ssen-$(date +%Y%m%d).dump
```

덤프 파일에는 학생 정보가 들어 있으니 저장소에 올리지 말고 안전한 곳에 보관하세요.

## 4. 복원 연습

백업은 **복원해 봐야** 믿을 수 있습니다. 운영 DB를 건드리지 않고 새 DB에 복원해서 확인하세요. 아래는 로컬 Docker(`ssen-db`)에서 실제로 해 본 절차입니다(덤프 → 새 DB 복원 → 서버를 그 DB로 띄워 학생 로그인까지 확인).

```bash
# 1) 비어 있는 새 DB를 만들고 복원
createdb -h localhost -p 5433 -U ssen ssen_restore_check
pg_restore -h localhost -p 5433 -U ssen -d ssen_restore_check --no-owner --no-privileges ssen-20260101.dump

# 2) 건수 비교 (운영과 같아야 함)
psql -h localhost -p 5433 -U ssen -d ssen_restore_check -tAc \
  "select (select count(*) from app_user), (select count(*) from classroom), (select max(version::int) from flyway_schema_history)"

# 3) 복원한 DB로 서버를 띄워 로그인 확인 후 정리
dropdb -h localhost -p 5433 -U ssen ssen_restore_check
```

`pg_restore`가 오류 없이 끝나고(종료 코드 0), 건수와 마이그레이션 버전이 원본과 같고, 복원한 DB로 띄운 서버에서 기존 학생이 로그인되면 성공입니다.

실제 장애로 운영 DB를 되살려야 할 때는 Railway에서 새 Postgres 서비스를 만들어 같은 방식으로 복원한 뒤, 서버의 `DB_URL` 등을 새 서비스로 바꿉니다.

## 5. 운영 주의사항

- **`SPRING_PROFILES_ACTIVE=prod`는 필수**입니다. 기본 프로필(`local`)로 뜨면 개발용 교사 계정이 운영 DB에 등록됩니다.
- `JWT_SECRET`에는 기본값이 없어서 빠뜨리면 서버가 시작하지 않습니다. 바꾸면 모든 사용자의 로그인이 풀립니다.
- `TEACHER_ACCOUNTS`는 비어 있으면 교사가 없을 뿐이고, 형식이 틀리면 서버가 시작하지 않습니다. **목록에서 항목을 지워도 이미 등록된 교사 계정은 사라지지 않습니다.** 번호를 바꾸려면 값을 바꾸고 다시 배포합니다.
- 교사 권한은 학교·이름·번호 4자리로 보호되고, 로그인 시도는 5회/5분으로 제한됩니다. 번호는 추측하기 쉬운 값을 피하고 Railway Variables에만 둡니다.
- 학생이 참여 코드를 5번 틀리면 5분간 잠깁니다(서버 메모리 기반이라 재시작하면 초기화됩니다).
- 이미지의 JVM 힙은 컨테이너 메모리의 75%로 잡습니다(`MaxRAMPercentage`). 로컬 도커에서 `prod`로 띄워 유휴 상태일 때 약 400MiB를 썼으니, 운영에서는 Railway 메트릭으로 메모리를 지켜보고 부족하면 늘립니다.
- 앱 주소(도메인)를 바꾸면 `CORS_ALLOWED_ORIGINS`도 함께 바꿔야 합니다. 안 바꾸면 브라우저에서 모든 API 호출이 막힙니다.
