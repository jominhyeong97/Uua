# Uua — AI 에이전트용 메모리 엔진

> 무상태(stateless) LLM 세션 사이로 작업 맥락을 자동으로 날라주는 Spring Boot 백엔드.
> 저장된 메모리를 임베딩 → pgvector → 의미검색 + 최신성 랭킹으로 골라, 토큰 예산 안에서
> "컨텍스트 팩"으로 조립해 돌려준다. **MCP 서버로 Claude/Gemini가 직접 호출**(dogfooding).

🌐 라이브:
- 헬스 — [https://uua.onrender.com/actuator/health](https://uua.onrender.com/actuator/health)
- 사용량 — [https://uua.onrender.com/api/usage/summary](https://uua.onrender.com/api/usage/summary) ← "비용 0"을 숫자로 증명

## 한 줄 차별점

"제2의 뇌 Q&A"(NotebookLM·Notion AI·Mem) 레드오션이 **아니다**. 차별점은 UI가 아니라
**메모리 엔진 내부** + **에이전트 네이티브(MCP) 제공**. 같은 결의 카테고리: Mem0 · Letta/MemGPT · Zep.

## 아키텍처

```
                  ┌─────────────────────┐
                  │ Claude Code (LLM)   │
                  └──────────┬──────────┘
                             │ stdio MCP
                  ┌──────────▼──────────┐
                  │ uua_mcp.py (Python) │  ← recall_context 도구 1개
                  └──────────┬──────────┘
                             │ HTTP
┌──────────────┐  ┌──────────▼──────────┐  ┌─────────────────┐
│ Gemini API   │◄─┤ Spring Boot Backend ├─►│ Neon Postgres   │
│ (embedding)  │  │ Java 21 · Gradle    │  │ + pgvector(768) │
└──────────────┘  └──────────┬──────────┘  └─────────────────┘
                             │
                  Render Free Tier (Docker)
```

## v1 달성 현황 (2026-05-31)

| # | 단계 | 상태 | 표면 |
|---|---|---|---|
| ① | 뼈대 + 공개 배포 + pgvector | ✅ | `GET /actuator/health` |
| ② | 쓰기 API + Gemini 임베딩 | ✅ | `POST /api/memories` |
| ③ | 읽기 API (cosineSim + recency) | ✅ | `POST /api/context` |
| ④ | Ingest API (청킹 + 스로틀) | ✅ | `POST /api/sessions/{id}/ingest` |
| ⑤ | Usage 가드 + 사용량 요약 | ✅ | `GET /api/usage/summary` |
| ⑥ | recall@K + MCP 래퍼 + 문서 | ✅ | `mcp/`, `eval/`, `docs/adr/` |

**테스트**: 56/56 통과 (단위 + 슬라이스 + Testcontainers 통합)
**라이브 검증**: 4건 curl 스모크 + recall@20 = 4/4 = 100% (시드 데이터셋)
**비용**: $0 — Render 무료 웹서비스 + Neon 무료 Postgres + Gemini 무료티어

## 핵심 알고리즘

읽기 경로(`POST /api/context`)의 점수식:

```
finalScore = cosineSim + 0.1 · exp(-ageInDays / 30)
```

- `cosineSim` = `1 - pgvector_distance` (`<=>` 연산자)
- 점수 내림차순 정렬 → 토큰 예산 안에서 그리디로 청크 누적
- 누적 토큰이 `maxTokens` 초과하는 청크는 **건너뛰지 않고 거기서 멈춤**
- 인용 번호 붙여 `pack` 문자열로 조립

## 기술 스택

| 영역 | 선택 | 이유 |
|---|---|---|
| 언어/런타임 | Java 21, Spring Boot 3.5 | 백엔드 표준 + 익숙함 |
| DB | Postgres 16 + pgvector | 벡터검색 ANN, 무료티어 호환 |
| 임베딩 | Gemini `gemini-embedding-001` (768차원) | 무료티어 RPD ~1500 |
| 호스팅 | Render(앱) + Neon(DB) | 둘 다 무료 + 만료 없음 |
| MCP | Python FastMCP | 공식 SDK 가장 성숙 + JVM 콜드스타트 회피 ([ADR-004](docs/adr/004-usage-guard-decorator.md)) |
| 마이그레이션 | Flyway V1/V2 | `memory_item`, `usage_log` |
| 테스트 | JUnit 5 + Testcontainers + MockWebServer | 단위/슬라이스/통합 3층 |

## 빠른 실행 (로컬)

```bash
# 백엔드
./gradlew bootRun
# → docker-compose가 pgvector 컨테이너 자동 기동 (compose.yaml 참조)
# → http://localhost:8080/actuator/health 200

# MCP 래퍼 (Claude Code dogfood)
cd mcp
python -m venv .venv
.venv\Scripts\Activate.ps1   # Windows
pip install -r requirements.txt
python uua_mcp.py --test-call "테스트 쿼리"   # HTTP 경로만 검증
claude mcp add -s user uua "$PWD\.venv\Scripts\python.exe" "$PWD\uua_mcp.py"

# recall@K 평가
cd ..\eval
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python runner.py
```

## API 요약

### `POST /api/memories` — 단일 메모리 저장 (단계 ②)
```json
// req
{"text": "...", "projectKey": "uua", "sessionKey": "?"}
// resp 201
{"id": 1, "projectKey": "uua", "createdAt": "...", "tokenCount": 4}
```

### `POST /api/sessions/{sessionId}/ingest` — 긴 세션 덤프 청킹 인입 (단계 ④)
```json
// req
{"text": "수천~수만 글자", "projectKey": "uua"}
// resp 201
{"sessionId": "...", "chunks": 7, "ids": [101, 102, ...], "tokensTotal": 3450}
```
중간 청크 실패 시 부분 커밋 + 503 ([ADR-003](docs/adr/003-ingest-partial-commit.md)).

### `POST /api/context` — 컨텍스트 팩 조립 (단계 ③)
```json
// req
{"query": "어제 결정한 카프카 방식", "projectKey": "uua", "maxTokens": 1000, "topK": 20}
// resp 200
{
  "pack": "다음은 관련 메모리입니다:\n[1] ...\n[2] ...",
  "items": [{"text": "...", "source": "memory:42", "createdAt": "...", "score": 0.81}],
  "usedTokens": 480,
  "maxTokens": 1000
}
```

### `GET /api/usage/summary` — 사용량 요약 (단계 ⑤)
"비용 0" 숫자 증명용. `today` + `last7Days` + `limits`(dailyCap/remainingToday/killSwitchEnabled).

## 주요 결정 기록 (ADR)

자세한 ADR(Architecture Decision Records)은 [`docs/adr/`](docs/adr/) 참고:

1. [Approach A 선택 → B 확장 인터페이스](docs/adr/001-approach-a.md)
2. [벡터검색은 네이티브 SQL (JPA 파생쿼리 불가)](docs/adr/002-native-sql-for-vector-search.md)
3. [Ingest 부분 커밋 (원자성 vs RAG 가치)](docs/adr/003-ingest-partial-commit.md)
4. [Usage 가드는 데코레이터 패턴](docs/adr/004-usage-guard-decorator.md)
5. [`text-embedding-004` → `gemini-embedding-001` 모델 마이그레이션](docs/adr/005-embedding-model-migration.md)
6. [`Size` 위반의 413 분기는 `max` 속성으로 좁힌다](docs/adr/006-size-413-disambiguation.md)

## 작업 일지 / 진행 상태

- 설계 SSOT: [`docs/DESIGN.md`](docs/DESIGN.md) (Status: APPROVED, gstack `/office-hours` 산출)
- 작업 핸드오프: [`HANDOFF.md`](HANDOFF.md) ("지금 어디까지 됐고 다음에 뭘 하나")

## 면접 한 줄

> "AI 에이전트용 메모리 백엔드를 만들었습니다. 무엇을·언제 꺼낼지 결정해 토큰 예산에 맞춘 컨텍스트 팩을 조립하고 MCP로 제공합니다. Render+Neon+Gemini 무료티어로 비용 0을 [`/api/usage/summary`](https://uua.onrender.com/api/usage/summary) 엔드포인트로 숫자로 증명합니다. 백엔드는 Spring Boot, MCP는 공식 SDK가 가장 성숙한 Python으로 얇게 감쌌습니다."
