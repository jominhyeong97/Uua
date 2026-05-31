# Uua — 작업 HANDOFF

> 이 프로젝트에서 Claude(Claude Code)를 켰을 때 **가장 먼저 읽는 파일**입니다. (작업은 IntelliJ가 아니라 Claude Code에서 진행 중)
> 전체 설계는 `docs/DESIGN.md` (Status: APPROVED). 이 핸드오프는 "지금 어디까지 됐고 다음에 뭘 하나"를 담습니다.
> 최종 갱신: 2026-05-31 (단계 ②~⑤ 라이브 검증 완료 + 단계 ⑥B MCP 래퍼 코드 작성 — Python 설치 대기 중)

---

## ⏭️ 여기서 시작 (다음 세션)

**단계 ②~⑤ 라이브 검증 완료.** 4건 curl 스모크(쓰기/인입/읽기/usage summary) 모두 통과. Render 라이브 + Gemini 키 등록 + usage_log 정상 누적 확인.
**단계 ⑥B (MCP 래퍼) 코드 작성됨.** `mcp/uua_mcp.py` (Python FastMCP + httpx, recall_context 도구 1개). 검증은 Python 설치 후 다음 세션.

다음 세션 시작 시 **3가지를 순서대로**:

### (a) Windows에 Python 3.10+ 설치 (~5분, 형이 직접)
```powershell
winget install -e --id Python.Python.3.12
```
또는 https://www.python.org/downloads/ 에서 인스톨러. **반드시 "Add to PATH" 체크.** 설치 후 새 PowerShell 열어 `python --version` → `Python 3.12.x` 확인.

### (b) MCP 서버 venv 셋업 + 스모크
```powershell
cd "C:\Users\user\개인 프로젝트\Uua\mcp"
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python uua_mcp.py --test-call "카프카 결정"
```
기대: `/api/context` 응답 JSON(pack/items/usedTokens/maxTokens)이 그대로 출력.

### (c) Claude Code에 MCP 등록 + dogfooding 시작
`mcp/README.md`의 "Claude Code에 MCP 등록" 섹션 따라 config 편집 + 재시작. 등록 끝나면 새 세션에서:
> "uua에서 어제 결정한 카프카 결정 메모리 가져와줘"
하면 자동으로 recall_context 호출.

### (d) 단계 ⑥A: recall@K 평가셋
- `eval/golden.json`에 (query, expected_chunk_ids[]) 5-6쌍 손으로 작성
- `eval/runner.py`가 `/api/context` 호출 → hit rate 계산 → 리포트
- 청크 ID는 사전에 ingest해서 확보

### (e) 단계 ⑥C: README + ADR
- 루트 `README.md` (한 줄 요약 + ASCII 다이어그램 + 라이브 링크 + 실행법)
- `docs/adr/` 6개 (Approach A / 네이티브 SQL / 부분 커밋 / 데코레이터 / 모델 변경 / 413 분기)

다음 세션 시작 시 **2가지를 순서대로**:

### (a) 단계 ②~⑤ 라이브 마무리 — Gemini 키 등록 + 정상 스모크 4건
1. https://aistudio.google.com/app/apikey 에서 **무료 Gemini API 키** 발급(결제계정 불필요).
2. Render 대시보드 → Uua 웹서비스 → Environment → `GEMINI_API_KEY` 추가 → Save.
3. 자동 재배포 완료 후 curl 4건 확인:
   ```bash
   # 쓰기: 201
   curl -X POST https://uua.onrender.com/api/memories \
     -H 'Content-Type: application/json' \
     -d '{"text":"render smoke test","projectKey":"uua-render"}'

   # 인입: 201 + chunks≥1
   curl -X POST https://uua.onrender.com/api/sessions/smoke-1/ingest \
     -H 'Content-Type: application/json' \
     -d '{"text":"오늘 카프카 보상 트랜잭션을 사가 패턴으로 결정했다... (긴 텍스트)","projectKey":"uua-render"}'

   # 읽기: 200 + items에 위 메모가 1순위로 나와야 함
   curl -X POST https://uua.onrender.com/api/context \
     -H 'Content-Type: application/json' \
     -d '{"query":"render smoke","projectKey":"uua-render","maxTokens":1000}'

   # 사용량 요약: 200 + today.successes ≥ 2
   curl https://uua.onrender.com/api/usage/summary
   ```
4. 결과를 Notion에 **"단계②~⑤ 구현 기록"** 페이지로 남김.

### (b) 단계 ⑥ = 미니 평가셋(recall@K) + MCP 래퍼 + README/ADR
- 손으로 만든 정답지(쿼리 X에 대해 mnemo top-K가 손으로 고른 청크를 포함했는가)로 recall@K 측정
- 얇은 stdio MCP 래퍼: `recall_context` 도구가 `/api/context` 호출
- 본인 Claude Code/Gemini에 MCP 등록해 dogfooding 시작
- README + ADR: 임베딩/벡터검색/토큰버짓/MCP 결정 기록

---

## 0. 한 줄 요약 / 작업자에게

**Uua = AI 에이전트용 메모리 엔진.** 무상태 LLM 세션 사이로 작업 맥락을 자동으로 날라주는 Spring Boot 백엔드.
저장한 맥락을 임베딩→pgvector 저장하고, 새 세션/질문 시 의미검색+최신성 랭킹으로 골라 토큰 예산 안에서
"컨텍스트 팩"으로 조립해 돌려준다. MCP 서버로 Claude/Gemini가 직접 호출(dogfooding).

**작업자(사용자) = 조민형, 주니어 백엔드(Java/Spring), 구직 중.** 임베딩/벡터검색/top-K 등 RAG 용어를
아직 잘 모름 → **어려운 용어는 풀어서 설명하며 진행할 것.** 이 프로젝트는 실용(매일 씀) + 구직 포트폴리오 두 목적.
용어 사전은 `docs/DESIGN.md`의 "용어 사전" 참고.

이 프로젝트는 `/office-hours`(gstack)로 설계를 확정한 결과물이다. 설계 재논의 불필요. 바로 구현 단계.

---

## 1. 지금 상태 (DONE)

- ✅ `/office-hours`로 설계 승인 완료 → `docs/DESIGN.md` (SSOT).
- ✅ Spring Boot 뼈대 생성됨 (start.spring.io):
  - **Gradle · Java 21 · Spring Boot 3.5.14**
  - group `com.uua`, 메인클래스 `src/main/java/com/uua/UuaApplication.java`
  - 의존성: web, actuator, data-jpa, validation, postgresql(driver), lombok, **docker-compose support**
- ✅ 승인 설계문서를 `docs/DESIGN.md`로 복사해 둠.
- ✅ **(2026-05-26) git 분리 완료**: Uua 폴더를 독립 저장소로 `git init`(브랜치 `main`).
  이전엔 홈 디렉터리(`C:/Users/user`)에 git이 잘못 잡혀 있었음(README 1개짜리 빈 껍데기, 그대로 둠).
- ✅ **(2026-05-26) 단계 ① 로컬 검증 완료** (커밋 `b47fb4f`):
  - `compose.yaml` → `pgvector/pgvector:pg16`, 호스트 포트 `5432:5432` 고정.
  - `build.gradle` → `flyway-core` + `flyway-database-postgresql` 추가.
  - `application.properties` → datasource(로컬은 docker-compose 자동연결, 배포는 `SPRING_DATASOURCE_*` env 폴백),
    `ddl-auto=validate`, flyway 활성화, actuator health 노출.
  - `src/main/resources/db/migration/V1__init.sql` → `CREATE EXTENSION vector` + `memory_item` 테이블(`vector(768)`).
  - 검증됨: `./gradlew bootRun` → pgvector 컨테이너 자동기동 → Flyway v1 적용 →
    `/actuator/health` 200 → DB에 `vector` 확장 + `memory_item` 테이블 확인.
- ✅ **(2026-05-27) 단계 ① 공개배포 완료** (커밋 `8723ac2`):
  - `Dockerfile`(멀티스테이지 JDK빌드→JRE실행, `MaxRAMPercentage=60 + SerialGC`로 512MB OOM 방지), `.dockerignore`,
    `server.port=${PORT:8080}` 추가.
  - GitHub public 저장소: https://github.com/jominhyeong97/Uua
  - **Neon**(무료 Postgres, pgvector) DB 생성 → 로컬에서 jar를 Neon에 붙여 Flyway v1 적용까지 선검증(de-risking).
  - **Render**(무료 웹서비스, Docker, Singapore) 배포 + `SPRING_DATASOURCE_*` env(Neon) → **라이브: https://uua.onrender.com**.
  - 배포 전과정 Notion 기록: "단계① 공개배포 기록 (Render + Neon)" 페이지.
- ✅ **(2026-05-31) 단계 ② 쓰기 API 코드 완료 + 로컬 검증** (커밋 `2ca1d50`):
  - **신규 패키지**: `com.uua.embedding`(`EmbeddingClient` 인터페이스 + `EmbeddingException` + `GeminiEmbeddingClient`(RestClient) + `EmbeddingProperties`),
    `com.uua.memory`(`MemoryItem` JPA 엔티티(+ embedding `@Transient`) + `MemoryRepository` + `MemoryJdbcRepository`(단일 INSERT RETURNING) + `MemoryService`(@Transactional) + `MemoryController`(POST /api/memories) + DTO 2개),
    `com.uua.common`(`GlobalExceptionHandler` — 400/413/503 매핑).
  - **설정**: `application.properties`에 `gemini.*` 5개 키 추가(env 폴백), `UuaApplication`에 `@ConfigurationPropertiesScan`.
  - **테스트(11개 통과)**: `GeminiEmbeddingClientTest`(MockWebServer, 6케이스), `MemoryControllerTest`(@WebMvcTest, 4케이스), `MemoryCreateIntegrationTest`(Testcontainers + pgvector, 1케이스).
  - **로컬 curl 스모크 3/4 통과**(400/413/503 + 행 미저장 확인). 정상 201은 GEMINI_API_KEY 필요 → 다음 세션 (a)에서.
  - **빌드 우회 2개**: `gradle.properties` + `build.gradle`에 `buildDir = C:/temp/uua-build` (한글 경로 워커 JVM `ClassNotFoundException` 회피), MockWebServer 4.12.0 명시 버전.
  - **자잘한 함정 1개**: V1__init.sql 주석 수정 시 Flyway checksum mismatch — 원복하고 옛 모델명은 `EmbeddingProperties` Javadoc에 노트로.
  - Notion 작성물: 📐 단계② 상세 PRD — 쓰기 API(설계), (다음 세션) "단계②·③ 구현 기록" 페이지 작성 예정.
- ✅ **(2026-05-31) 단계 ③ 읽기 API 코드 완료 + 로컬 검증** (커밋 `4ebfd68`):
  - **신규 패키지** `com.uua.context`: `ContextRequest`/`ContextResponse`(+ `ContextItem`) DTO 레코드 + `ContextController`(POST /api/context, 결과 0건도 200) + `ContextService`(점수 계산 + 그리디 조립, `Clock` 주입으로 테스트에서 시간 고정 가능).
  - **기존 파일 수정**: `MemoryJdbcRepository.search(projectKey, queryVec, k)` 구현 (`<=>` 코사인 거리 ORDER BY + `SearchHit` 레코드), `GlobalExceptionHandler`에 `query` 필드 413 매핑 확장(`LONG_BODY_FIELDS={"text","query"}`), `UuaApplication`에 `Clock systemUTC` 빈 등록.
  - **알고리즘**: `cosineSim = 1 - distance` + `recency = exp(-ageDays/30)` → `finalScore = cosineSim + 0.1·recency` 내림차순 → token 예산 안에서 그리디 누적(초과 직전에 멈춤).
  - **테스트(13개 통과, 누적 25개)**: `ContextServiceTest`(6) — 정렬/그리디/0건/임베딩 503 전파/source 형식/topK 전달, `ContextControllerTest`(5) — 400/413/maxTokens 400/503/정상 200, `ContextSearchIntegrationTest`(2) — Testcontainers + pgvector, 키워드별 결정적 fake 벡터로 의미 ordering + projectKey 격리.
  - Notion 작성물: 📐 단계③ 상세 PRD — 읽기 API.
- ✅ **(2026-05-31) 단계 ④ Ingest API 코드 완료 + 로컬 검증** (커밋 `9e0344c`):
  - **신규 패키지** `com.uua.ingest` (9 파일): `Chunker`(고정창 2000자 분할), `Sleeper`/`ThreadSleeper`(스로틀 추상화 — 테스트는 no-op), `IngestProperties`(throttle-millis=200, window-chars=2000), `IngestRequest`/`IngestResponse` DTO, `IngestPartialFailure`(부분 실패 예외), `IngestController`(POST /api/sessions/{id}/ingest, @Validated path variable @Size), `IngestService`(TransactionTemplate으로 청크별 트랜잭션).
  - **기존 파일 수정**: `application.properties`에 `ingest.*` 2개 키 추가, `GlobalExceptionHandler`에 `IngestPartialFailure` 503+committed/failedAt 매핑 + `ConstraintViolationException` 400 매핑 추가 + 413 분기를 "Size max=8000일 때만"으로 좁힘(ingest text max=200000과 구분).
  - **부분 커밋 결정**: 100청크 중 N번째 임베딩 실패 시 N-1개는 DB 유지 + 503 응답. v1엔 재시도 큐가 없어 전체 롤백 시 영구 실패 → RAG는 부분 데이터도 가치 있음.
  - **테스트(17개 통과, 누적 42개)**: `ChunkerTest`(7) — 0/짧음/딱맞음/1초과/정확배수/한글/잘못된 윈도우, `IngestServiceTest`(4) — 빈 text/정상 2청크 sleep 호출수/중간실패 부분커밋/첫 청크 실패 committed=0, `IngestControllerTest`(4) — 201/400빈/400 200001자/503, `IngestIntegrationTest`(2) — Testcontainers 4000자→2청크 source=INGEST + 4001자→3청크 마지막 1자.
  - **함정 1개 발견·수정**: text 필드명 단계②(max=8000)와 단계④(max=200000)가 충돌 → 413 분기에서 `FieldError.getArguments()`로 max 값 직접 확인.
  - Notion 작성물: 📐 단계④ 상세 PRD — Ingest API.
- ✅ **(2026-05-31) 단계 ⑤ 비용/남용 방어 + Usage Summary 코드 완료 + 로컬 검증** (커밋 `805ada4`):
  - **신규 패키지** `com.uua.usage` (7 파일): `UsageProperties`(embeddingEnabled, dailyCap), `UsageJdbcRepository`(insert + 4 집계 쿼리), `UsageRecorder`(`@Transactional(REQUIRES_NEW)` + DataAccessException 삼키기), `UsageLimiter`(UTC 자정 기준 SUCCESS 카운트 체크), `UsageSummary`(Today/LastWindow/Limits 중첩 record), `UsageQueryService`(Clock 주입), `UsageController`(GET /api/usage/summary).
  - **신규** `com.uua.embedding.UsageGuardedEmbeddingClient`: **데코레이터 패턴**. GeminiEmbeddingClient를 감싸 kill switch → limiter.check() → delegate.embed() → recorder.record() 순서로 처리. EmbeddingClient 인터페이스의 유일 구현체.
  - **기존 파일 수정**: `V2__usage_log.sql` 추가, `EmbeddingException.Reason`에 KILLED/DAILY_LIMIT enum 추가, `GeminiEmbeddingClient`에서 `implements EmbeddingClient` + `@Override` 제거(데코레이터가 인터페이스 소유), `application.properties`에 `usage.*` 2개 키 추가.
  - **테스트(14개 통과, 누적 56개)**: `UsageLimiterTest`(4) — 한도 미만/같음/초과 + UTC 자정 since 검증, `UsageQueryServiceTest`(3) — Today+Week+Limits 조립 + 킬스위치 + remainingToday 0 clamp, `UsageGuardedEmbeddingClientTest`(5) — KILLED/DAILY_LIMIT/SUCCESS/FAILURE 4경로 + approxTokens 검증, `UsageControllerTest`(1) — @WebMvcTest summary JSON 구조, `UsageIntegrationTest`(1) — Testcontainers + MockWebServer(Gemini 모방), POST /api/memories → usage_log SUCCESS 1행 → summary 반영.
  - **함정 2개**: GeminiEmbeddingClient에서 `implements EmbeddingClient` 떼면 `@Override`도 함께 제거 필요(컴파일 에러 한 번 났음), Mockito `private static ArgumentMatcher<Instant> any()` 헬퍼는 매처를 반환해 Instant 시그니처와 안 맞음 — `ArgumentMatchers.any(Instant.class)` 직접 호출이 정답.
  - Notion 작성물: 📐 단계⑤ 상세 PRD — 비용/남용 방어 + Usage Summary.
- ✅ **(2026-05-31) 단계 ②~⑤ 라이브 스모크 4건 통과** (커밋 `f03b82e` 후):
  - Gemini 키 발급 → Render `GEMINI_API_KEY` 등록 → 자동 재배포 성공.
  - **Render Docker 빌드 실패 1회 해결**: `build.gradle`의 `layout.buildDirectory.set(file('C:/temp/uua-build'))`이 Linux 빌드에서 `/app/C:/temp/uua-build`로 출력 → Dockerfile의 `COPY --from=build /app/build/libs/*.jar` 가 빈 경로를 매칭해 실패. `OperatingSystem.current().isWindows()` 분기로 Windows에서만 적용하도록 수정 (커밋 `f03b82e`).
  - 4건 curl 스모크 PowerShell(Invoke-RestMethod + UTF-8 bytes 변환)로 통과:
    1) `POST /api/memories` → 201, id=1
    2) `POST /api/sessions/smoke-1/ingest` → 201, chunks=1, tokens=17
    3) `POST /api/context "카프카 결정"` → 200, items에 인입한 텍스트 포함
    4) `GET /api/usage/summary` → today: embedCalls=3, successes=3, failures=0, tokensTotal=22 (4+17+1 정확히 추적)
  - PowerShell 함정: `curl`은 `Invoke-WebRequest` 별칭이라 `-H`/`-d` 안 먹음. `Invoke-RestMethod` + `[System.Text.Encoding]::UTF8.GetBytes($json)` 패턴이 정답(한글 인코딩 정상).
- ✅ **(2026-05-31) 단계 ⑥B MCP 래퍼 작성 + 라이브 검증 + dogfood 등록** (커밋 `16e1464`, `50b3712`):
  - `mcp/uua_mcp.py` — FastMCP 기반, 도구 `recall_context(query, project_key?, max_tokens?, top_k?)` 1개 노출. 내부는 `POST {UUA_BASE_URL}/api/context` 호출. `--test-call` 모드(MCP 프로토콜 없이 HTTP만 검증) 포함.
  - `mcp/requirements.txt`(`mcp[cli]>=1.0.0`, `httpx>=0.27.0`), `mcp/README.md`, `mcp/.gitignore`.
  - **Python 3.12.10 설치**(winget), `mcp/.venv` 생성, `pip install` 36개 패키지 성공.
  - `--test-call "카프카 결정"` 결과: items[0]=memory:2(score 0.80), items[1]=memory:1(score 0.61). 알고리즘 정렬 + 한글 인코딩 정상.
  - **D2 결정**: `UUA_PROJECT_KEY` 기본값을 `uua` → `uua-render`로 수정(`50b3712`). 단계②~⑤ 라이브 검증과 dogfood 본 시작을 같은 키에 누적.
  - Claude Code 등록: `claude mcp add -s user uua ...` → `✓ Connected`. user scope로 모든 세션 가시.
- ✅ **(2026-05-31) 단계 ⑥A recall@K 평가셋 코드 + 라이브 측정** (커밋 예정):
  - `eval/runner.py` — golden.json 로드 → /api/context 호출 → "expected_sources 중 1개 이상이 items에 들어왔나"로 hit/miss 집계 → 콘솔 표 + `results-{timestamp}.json` 저장. Render 콜드스타트 warmup 포함.
  - `eval/golden.json` — v1 시드 4 페어(memory:1 + memory:2 기반 쿼리들).
  - `eval/requirements.txt`(`httpx`), `eval/README.md`(셋업 + Assignment + 점수 해석 + 튜닝 포인트), `eval/.gitignore`(`.venv/`, `results-*.json`).
  - **별도 venv** (`eval/.venv`) — mcp/와 분리.
  - **라이브 측정 결과**: recall@20 = **4/4 = 100.0%**. 카프카(0.80), ORBIT(0.77), 사가 패턴(0.83), render smoke(0.94). 첫 쿼리 40.7s(콜드스타트), 나머지 ~500-700ms.
  - **함정**: 100%는 시드 작아서 "가짜". 실제 handoff ingest 후 다시 측정해야 진짜 신호.
  - **환경 함정**: Claude Code 도구 환경의 PowerShell은 winget으로 설치한 Python을 PATH에서 못 찾음(Windows Store 스텁 우선) → 절대경로 `C:\Users\user\AppData\Local\Programs\Python\Python312\python.exe` 사용 필요.
  - **형 숙제 (다음 세션)**: handoff 문서 3-5개 모아서 `POST /api/sessions/{id}/ingest`로 인입 → 새 memory_id 확보 → golden.json 확장 → recall 재측정.

---

## 2. 다음 할 일 — 단계 ② 라이브 마무리 → 단계 ③ (읽기 API)

자세한 시작 순서는 맨 위 "⏭️ 여기서 시작" 섹션 참고. 아래 무료 스택은 단계 ② 이후도 동일.

### 무료 스택 (2026-05-26 공식 페이지 조사, "비용 0" 성공기준 ⓑ 근거)

| 구성 | 선택 | 비고 / 함정 |
|---|---|---|
| 컴퓨트(앱) | **Render 무료 웹서비스** | $0, 15분 유휴 슬립(콜드스타트 ~1분), 750h/월. ⚠️**RAM 512MB → JVM `-Xmx` 튜닝 필수(OOM 위험)** |
| DB | **Neon 무료** | pgvector 지원 ✓, 0.5GB, **만료 없음**, scale-to-zero(~350ms). **Render Postgres는 30일 만료라 쓰지 말 것** |
| 임베딩 | **Gemini `gemini-embedding-001`** | 무료티어 요금 0. ⚠️무료티어 입력은 구글 학습에 사용됨(프라이버시) + RPM/RPD 한도 → ingest 스로틀 |
| MCP | 로컬 stdio | 비용 0 |

- 배포 시 `SPRING_DATASOURCE_URL/_USERNAME/_PASSWORD` env로 Neon 연결(코드는 이미 env 폴백 준비됨).
- Neon에서 `CREATE EXTENSION vector` 가능(무료 플랜 확장 라이브러리 포함). Flyway V1이 그대로 실행됨.
- ⚠️ **임베딩 모델명 변경**: 설계 SSOT의 `text-embedding-004`는 `gemini-embedding-001`로 대체됨. 새 모델도 **무료 + 768차원 지원**(`output_dimensionality=768`)이라 `vector(768)` 스키마는 그대로 OK. 단계 ②에서 이 모델명으로 구현.

그 다음 **단계 ②**: `MemoryItem` 엔티티(@Entity, embedding은 JdbcTemplate/네이티브로 read/write) + `EmbeddingClient`(Gemini `gemini-embedding-001`) + 쓰기 API + 입력검증·임베딩 실패경로(503).

### 📒 문서화 규칙 (2026-05-26부터)
- **개발 순서: 개발규칙 + PRD를 먼저 작성 → 그 다음 코딩.**
- 모든 산출물(개발규칙·PRD·yml 설정 등)은 **Notion "Uua 프로젝트" 페이지** 하위에 문서화하며 진행(나중에 보고 공부용, orbit/LUVUM 프로젝트 스타일).
  - Notion 페이지: https://www.notion.so/Uua-36cff921786f8044bd84fa7acbda90b0

---

## 3. 구현 시 꼭 기억할 함정 (스펙 리뷰에서 나온 것)

`docs/DESIGN.md`에 다 있지만, 막히기 쉬운 핵심만:

- **벡터검색은 JPA 파생쿼리로 불가.** `@Query(nativeQuery=true)` 또는 `JdbcTemplate`로 `ORDER BY embedding <=> :q LIMIT :k`. 임베딩 컬럼(`vector(768)`)은 커스텀 타입 매핑 또는 네이티브로 read/write. → **이게 1순위 landmine.**
- **임베딩 모델/차원 고정**: Gemini **`gemini-embedding-001`**(설계의 `text-embedding-004` 대체), **768차원**(`output_dimensionality=768`) → DB 컬럼 `vector(768)`. 차원 틀리면 스키마 마이그레이션.
- **무료티어 RPM**: 내 ingest가 청크 수십 개를 한 번에 임베딩하면 *내가 먼저* 한도 초과 → ingest는 순차+딜레이 또는 batch embed로 스로틀.
- **임베딩 실패 시**: 503 반환 + 행 저장 안 함(쓰기 원자성). v1엔 재시도 큐 없음.
- **v1 범위 고정**: LLM 요약 없음(임베딩 API 하나만 의존), "대시보드"는 `GET /api/usage/summary` JSON으로, MCP는 기존 `/api/context` 감싸는 얇은 stdio 래퍼(마지막 단계).

핵심 알고리즘(청킹 500토큰 고정창 / `finalScore=cosineSim+0.1·exp(-age/30)` / 그리디 토큰버짓 조립) 의사코드와
`/api/context` 응답 JSON 예시는 `docs/DESIGN.md`의 "v1 구체 스펙" 섹션에 있음.

---

## 4. 전체 단계 로드맵 (v1 = Approach A)

1. ✅ 뼈대 부팅 + 공개 배포(/health) + pgvector compose
2. ✅ `MemoryItem` 도메인 + `EmbeddingClient`(Gemini) + 쓰기 API + pgvector 저장(네이티브 SQL) + 입력검증·실패경로
3. ✅ 읽기 API(top-K + 최신성 + 토큰버짓 컨텍스트 팩) + 출처 인용
4. ✅ 자동 핸드오프 인입(고정창 청킹 + 임베딩 스로틀)  *LLM 요약 없음*
5. ✅ 비용/남용 방어(입력가드·레이트리밋·일일상한+킬스위치·UsageLog) + `GET /api/usage/summary` + **라이브 4건 스모크 통과**
6. 🚧 미니 평가셋(recall@K) + 얇은 stdio MCP 래퍼 + dogfood 연결 + README/ADR
   - ✅ ⑥B MCP 래퍼: 작성 + --test-call 검증 + Claude Code 등록(user scope, Connected)
   - ✅ ⑥A 평가셋: 작성 + 라이브 측정(recall@20 = 4/4 = 100% on seed)
   - 🚧 ⑥C README/ADR 미착수

성공 기준: ⓐ매일 실사용 ⓑ공개 데모+사용량으로 "비용 0" 숫자 증명 ⓒrecall@K 측정값 ⓓ ADR.

---

## 5. 작업자 숙제 (코딩 전, 이번 주)

지금 쓰는 Gemini `handoff` 문서 3~5개를 모아, "새 세션 시작 시 이 중 무엇이·어떤 순서로 필요했는지"를 손으로 적어둘 것.
→ mnemo 읽기 경로의 정답지이자 6단계 평가셋(recall@K)의 기준이 됨.

---

## 6. 참고 경로

- **Notion "Uua 프로젝트"**: https://www.notion.so/Uua-36cff921786f8044bd84fa7acbda90b0 (모든 산출물 여기 하위에 문서화)
  - 📕 개발 규칙: https://www.notion.so/36cff921786f81769626d5ce6b81325e
  - 🔧 PRD — Uua v1: https://www.notion.so/36cff921786f81799164c0973d82af8e
- 승인 설계(SSOT, 레포 내): `docs/DESIGN.md`
- 원본 설계(gstack): `C:\Users\user\.gstack\projects\jobfit\user-main-design-20260526-131849.md`
- 사용자 전역 메모리: `C:\Users\user\.claude\projects\C--Users-user\memory\ai-jobfit-project.md` (프로젝트 이력)
- (구) stale 디렉터리: `C:\Users\user\IdeaProjects\jobfit\PLAN.md` — 폐기 대상(옛 jobfit 구상). 무시/삭제 가능.
