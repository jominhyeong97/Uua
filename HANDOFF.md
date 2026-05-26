# Uua — 작업 HANDOFF

> 이 파일은 IntelliJ에서 이 프로젝트를 열고 Claude(Claude Code)를 켰을 때 **가장 먼저 읽는 파일**입니다.
> 전체 설계는 `docs/DESIGN.md` (Status: APPROVED). 이 핸드오프는 "지금 어디까지 됐고 다음에 뭘 하나"를 담습니다.
> 최종 갱신: 2026-05-26

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

---

## 2. 다음 할 일 — 단계 ① 마무리(공개 배포) → 단계 ②

**단계 ①에서 남은 것: 공개 배포 1개.**

- **공개 배포**(설계의 deploy-first 원칙): 앱을 Railway/Render에 올려 공개 URL에서 `/actuator/health` 200.
  - ⚠️ **배포 전 확인**: 무료티어가 pgvector 확장을 지원하는지. (Render 무료 Postgres는 90일 만료 + 무료 웹서비스 슬립; Railway는 크레딧 소진형.)
  - 배포 시 `SPRING_DATASOURCE_URL/_USERNAME/_PASSWORD` env로 prod DB 연결(코드는 이미 env 폴백 준비됨).
  - prod DB에서 `CREATE EXTENSION vector` 권한 필요 — 관리형 Postgres가 vector를 제공/허용하는지 사전 확인이 핵심 함정.

그 다음 **단계 ②**: `MemoryItem` 엔티티(@Entity, embedding은 JdbcTemplate/네이티브로 read/write) + `EmbeddingClient`(Gemini text-embedding-004) + 쓰기 API + 입력검증·임베딩 실패경로(503).

---

## 3. 구현 시 꼭 기억할 함정 (스펙 리뷰에서 나온 것)

`docs/DESIGN.md`에 다 있지만, 막히기 쉬운 핵심만:

- **벡터검색은 JPA 파생쿼리로 불가.** `@Query(nativeQuery=true)` 또는 `JdbcTemplate`로 `ORDER BY embedding <=> :q LIMIT :k`. 임베딩 컬럼(`vector(768)`)은 커스텀 타입 매핑 또는 네이티브로 read/write. → **이게 1순위 landmine.**
- **임베딩 모델/차원 고정**: Gemini `text-embedding-004`, **768차원** → DB 컬럼 `vector(768)`. 차원 틀리면 스키마 마이그레이션.
- **무료티어 RPM**: 내 ingest가 청크 수십 개를 한 번에 임베딩하면 *내가 먼저* 한도 초과 → ingest는 순차+딜레이 또는 batch embed로 스로틀.
- **임베딩 실패 시**: 503 반환 + 행 저장 안 함(쓰기 원자성). v1엔 재시도 큐 없음.
- **v1 범위 고정**: LLM 요약 없음(임베딩 API 하나만 의존), "대시보드"는 `GET /api/usage/summary` JSON으로, MCP는 기존 `/api/context` 감싸는 얇은 stdio 래퍼(마지막 단계).

핵심 알고리즘(청킹 500토큰 고정창 / `finalScore=cosineSim+0.1·exp(-age/30)` / 그리디 토큰버짓 조립) 의사코드와
`/api/context` 응답 JSON 예시는 `docs/DESIGN.md`의 "v1 구체 스펙" 섹션에 있음.

---

## 4. 전체 단계 로드맵 (v1 = Approach A)

1. 뼈대 부팅 + 공개 배포(/health) + pgvector compose  ← **지금 여기**
2. `MemoryItem` 도메인 + `EmbeddingClient`(Gemini) + 쓰기 API + pgvector 저장(네이티브 SQL) + 입력검증·실패경로
3. 읽기 API(top-K + 최신성 + 토큰버짓 컨텍스트 팩) + 출처 인용
4. 자동 핸드오프 인입(고정창 청킹 + 임베딩 스로틀)  *LLM 요약 없음*
5. 비용/남용 방어(입력가드·레이트리밋·일일상한+킬스위치·UsageLog) + `GET /api/usage/summary`
6. 미니 평가셋(recall@K) + 얇은 stdio MCP 래퍼 + dogfood 연결 + README/ADR

성공 기준: ⓐ매일 실사용 ⓑ공개 데모+사용량으로 "비용 0" 숫자 증명 ⓒrecall@K 측정값 ⓓ ADR.

---

## 5. 작업자 숙제 (코딩 전, 이번 주)

지금 쓰는 Gemini `handoff` 문서 3~5개를 모아, "새 세션 시작 시 이 중 무엇이·어떤 순서로 필요했는지"를 손으로 적어둘 것.
→ mnemo 읽기 경로의 정답지이자 6단계 평가셋(recall@K)의 기준이 됨.

---

## 6. 참고 경로

- 승인 설계(SSOT, 레포 내): `docs/DESIGN.md`
- 원본 설계(gstack): `C:\Users\user\.gstack\projects\jobfit\user-main-design-20260526-131849.md`
- 사용자 전역 메모리: `C:\Users\user\.claude\projects\C--Users-user\memory\ai-jobfit-project.md` (프로젝트 이력)
- (구) stale 디렉터리: `C:\Users\user\IdeaProjects\jobfit\PLAN.md` — 폐기 대상(옛 jobfit 구상). 무시/삭제 가능.
