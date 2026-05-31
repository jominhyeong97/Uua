# Uua recall@K 미니 평가셋

DESIGN.md 성공기준 ⓒ("recall@K 측정값이 있다")의 산출물.

손으로 만든 정답지(`(query, expected_sources)` 페어)로 라이브 백엔드의 `/api/context`를 호출하고, "기대한 메모리가 top-K에 들어왔는가"로 점수를 매긴다.

## 셋업

Python 3.10+. mcp/ 와 별도 venv를 권장:

```powershell
cd "C:\Users\user\개인 프로젝트\Uua\eval"
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

## 실행

```powershell
python runner.py
```

출력 예:

```
Eval @ K=20, max_tokens=1000, base=https://uua.onrender.com
Loaded 4 golden pairs from golden.json
Warming up server (Render cold-start may take 30-60s)...

───────────────────────────────────────────────────────────────────────────
PASS  "카프카 결정"                       → hit memory:2 (score 0.80) [890ms]
PASS  "ORBIT 워크스페이스 영향도"           → hit memory:2 (score 0.65) [520ms]
PASS  "사가 패턴 트랜잭션"                  → hit memory:2 (score 0.58) [610ms]
PASS  "render smoke"                      → hit memory:1 (score 0.71) [490ms]
───────────────────────────────────────────────────────────────────────────
recall@20: 4/4 = 100.0%
Report saved: results-20260531T123045.json
```

## 환경변수

| Var | Default | 의미 |
|---|---|---|
| `UUA_BASE_URL` | `https://uua.onrender.com` | 백엔드 루트 (로컬 테스트 시 `http://localhost:8080`) |
| `UUA_HTTP_TIMEOUT` | `90` | 콜드스타트 대비 — 초 |

## golden.json 작성 가이드 (= DESIGN.md의 "The Assignment")

> 코드 짜기 전에 이번 주 안에: 지금 쓰고 있는 handoff 문서 3~5개를 실제로 모아서, "새 세션을 시작할 때 이 중 무엇이·어떤 순서로 필요했는지"를 손으로 적어봐라. 그게 mnemo 읽기 경로(무엇을 top-K로 뽑고 어떻게 랭킹할지)의 실제 정답지이자, 나중에 "내 손 동작 vs mnemo 동작"을 비교하는 평가셋이 된다.

**v1 시드** (`golden.json`)는 단계②~⑤ 라이브 검증에서 들어간 데이터 2건(memory:1, memory:2)으로 만든 4 쿼리.

### 실제 정답지로 확장하기

1. 손으로 쓰던 handoff/메모/결정문서 3~5개를 모은다.
2. 각각을 `POST /api/sessions/{sid}/ingest`로 인입 (PowerShell `Post-Json` 함수 활용).
3. 인입 후 응답의 `ids`에서 새 memory_id들을 메모.
4. "내가 새 세션 시작할 때 이걸 물었으면 좋겠다" 쿼리 5~10개 작성 + 각 쿼리에 해당하는 expected memory_id 매핑.
5. `golden.json`의 `pairs`에 추가.

```json
{
  "query": "마이크로서비스 분리 결정 이유",
  "project_key": "uua-render",
  "expected_sources": ["memory:7", "memory:11"],
  "description": "두 개의 ADR 청크가 둘 다 들어와야 의미 있음"
}
```

> `expected_sources`에 둘 이상 적어도 "**적어도 하나만** items에 들어오면 PASS". 둘 다 들어와야 PASS로 만들고 싶으면 두 쿼리로 분리.

## 점수 해석

- **recall@K** = "쿼리 중 기대 메모리를 적어도 1개 끌어온 비율"
- 100%여도 만족하지 말 것 — golden이 작거나 너무 쉬우면 가짜 100%
- 70~85%가 "정상 시스템 + 적당히 어려운 golden"의 신호. 60% 이하면 알고리즘/임베딩 모델/청킹 튜닝 신호

## 튜닝 포인트 (점수가 낮을 때)

1. **`finalScore` 가중치 (`w`, `tau`)** — `MemoryService.create()` 옆 `ContextService.RECENCY_WEIGHT/TAU` 상수. 0.1·30 기본
2. **청킹 윈도우** — `ingest.window-chars` (기본 2000). 너무 크면 청크 안의 노이즈, 너무 작으면 문맥 손실
3. **임베딩 모델** — `gemini.model`. `gemini-embedding-001` → 다른 모델 시도

수정 후 같은 golden으로 재돌리면 비교 가능 — 그게 "측정해서 개선했다" 스토리.

## 함정 노트

- **memory_id는 DB 시퀀스 의존**: V1 마이그레이션 재적용 또는 DB 갈아엎으면 ID가 달라짐 → golden 깨짐. 안정적 평가하려면 fresh DB에 한 번 ingest 후 ID 동결하고 그 이후엔 DB 안 건드림.
- **콜드스타트 첫 쿼리**: warmup 했어도 첫 호출은 ~5s 가능. `latency_ms`에서 보임.
- **결과 파일은 .gitignore**: 매 실행마다 새 파일 생성. 시간별 비교용으로 따로 보관하려면 의도적으로 커밋.
