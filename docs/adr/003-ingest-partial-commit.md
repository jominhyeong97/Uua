# 003. Ingest 부분 커밋 — 원자성보다 RAG 가치 우선

Date: 2026-05-31
Status: Accepted

## Context

단계 ②(단일 메모 쓰기)는 한 트랜잭션 안에 [임베딩 호출 → INSERT]를 묶었다. 임베딩 실패 시 행 저장 안 함(503). 단순하고 원자적.

단계 ④(`POST /api/sessions/{id}/ingest`)는 다르다:

- 입력: 긴 세션 덤프 (수천~수만 글자)
- 처리: 고정창 청킹(2000자) → N 청크 → 각 청크마다 임베딩 호출 → INSERT
- 위험: Gemini 무료 RPM 한계 + 100 청크 중 중간에 429/타임아웃 발생 가능

만약 전체를 한 트랜잭션으로 묶으면:
- 100청크 중 99번째에서 429 → 전체 롤백 → 90개 청크 임베딩 호출 비용 헛돈
- 재시도해도 또 어딘가에서 막히면 영구 실패
- 큰 ingest는 사실상 들어갈 수 없게 됨

## Decision

**청크 1개당 별도 트랜잭션.** `IngestService`가 `TransactionTemplate`을 사용해 청크마다 새 트랜잭션을 시작한다. 중간 청크에서 임베딩 실패하면 `IngestPartialFailure(committed=i-1, failedAt=i)`를 던져 컨트롤러가 503으로 매핑:

```json
{
  "error": "embedding_unavailable",
  "cause": "RATE_LIMIT",
  "committed": 3,
  "failedAt": 4
}
```

이미 커밋된 i-1개 청크는 DB에 유지된다.

왜 `TransactionTemplate`인가: 같은 클래스 내부 메서드에 `@Transactional` 박고 self-call하면 Spring AOP 프록시 우회로 트랜잭션 적용 안 됨. 다른 빈으로 분리하는 방법도 있지만 명시적 프로그래매틱 트랜잭션이 더 단순.

## Consequences

긍정:
- 큰 ingest가 부분이라도 들어감 — RAG 컨텍스트는 부분 데이터도 검색 결과로서 가치 있음 (DESIGN.md 성공기준 ⓐ "매일 실사용"에 부합)
- 사용자가 어디서 끊겼는지 정확히 알 수 있음 (`committed`, `failedAt`)
- 재시도 시 `failedAt`부터 시작하면 됨 (클라이언트 책임, v1엔 자동화 X)

부정:
- 원자성 깨짐 (의도된 결정) — "이 ingest는 들어갔거나 안 들어갔거나" 불변식 없음
- 부분 실패 후 같은 ingest를 다시 호출하면 같은 청크가 중복 저장됨 (v1엔 dedup 없음)
- 클라이언트가 부분 실패 케이스를 처리해야 함

비고:
- v2 옵션: 청크 해시 기반 dedup, 재시도 큐(임베딩 실패한 청크만 큐에 적재), 진행도 SSE 스트림
- 청크별 트랜잭션이 DB 부하를 늘리지 않는가: pgvector INSERT 자체는 가벼움. 100 청크면 100 트랜잭션 ≈ ms 단위. 임베딩 호출(네트워크 100~500ms)이 지배적.
