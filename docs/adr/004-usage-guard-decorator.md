# 004. Usage 가드는 데코레이터 패턴

Date: 2026-05-31
Status: Accepted

## Context

단계 ⑤에서 3가지 가드를 모든 임베딩 호출 앞뒤에 박아야 했다:

1. **킬스위치** — `usage.embedding-enabled=false`면 외부 호출 전에 503(KILLED) 차단
2. **일일 상한** — 오늘 SUCCESS 카운트가 `dailyCap` 도달이면 503(DAILY_LIMIT) 차단
3. **사용량 기록** — 성공/실패 무관 `usage_log`에 1행 (REQUIRES_NEW로 별 트랜잭션)

배치 옵션:

- (A) 3 호출자(`MemoryService`, `ContextService`, `IngestService`) 모두에 같은 로직 박기 → 중복 3배 + 변경 비용
- (B) `GeminiEmbeddingClient.embed()` 직접 박기 → HTTP 책임 + 가드 책임 혼재. 단위 테스트 부담 (가드 mock 필요)
- (C) **데코레이터** — `UsageGuardedEmbeddingClient implements EmbeddingClient`가 `GeminiEmbeddingClient`를 감싸 가드 더함. Spring 자동 와이어링에서 호출자는 인터페이스만 의존

## Decision

**옵션 C (데코레이터).**

```
Controller → Service → EmbeddingClient (interface)
                        │
                        └─→ UsageGuardedEmbeddingClient
                               │ kill switch
                               │ limiter.check()
                               │ delegate.embed()
                               │ recorder.record(outcome, latency)
                               ↓
                            GeminiEmbeddingClient (HTTP 전용)
```

구현:
- `GeminiEmbeddingClient`는 `implements EmbeddingClient`를 떼고 HTTP 책임만. `@Component`는 유지(Spring이 빈으로 알도록).
- `UsageGuardedEmbeddingClient`가 유일한 `EmbeddingClient` 빈 — 호출자 3개 서비스가 자동 와이어링으로 받음.
- 테스트의 `@Primary EmbeddingClient` fake는 그대로 작동 — 데코레이터 통째 대체.

## Consequences

긍정:
- 호출자 서비스 3개 코드는 **한 줄도 안 바뀜** — 인터페이스만 의존하니까
- 가드 로직이 한 클래스에 집중 → 단위 테스트도 한 곳에서 (4경로: KILLED / DAILY_LIMIT / SUCCESS / FAILURE)
- `GeminiEmbeddingClient`는 순수 HTTP 클라이언트로 남아 가드 변경 시 영향 없음 — 기존 6개 테스트도 그대로

부정:
- `GeminiEmbeddingClient`가 더 이상 `EmbeddingClient` 인터페이스를 구현하지 않는다는 게 처음 보는 사람한텐 헷갈림 — Javadoc으로 명시
- `@Override` 어노테이션을 같이 떼야 컴파일됨 — 한 번 컴파일 실패로 발견(빠른 피드백)
- 빈 정의 시 `EmbeddingClient` 구현체가 2개가 되면 `@Primary` 충돌 발생 가능 — `@Primary`는 테스트 fake에서만 사용

비고:
- 향후 가드 추가(예: per-project 쿼터, 시간당 레이트리밋) 시 같은 데코레이터에 한 줄씩 추가
- v2엔 데코레이터 체인(`InterceptingClient` 패턴)으로 분리 가능
