# 002. 벡터검색은 네이티브 SQL (JPA 파생쿼리 불가)

Date: 2026-05-27
Status: Accepted

## Context

`POST /api/context`의 핵심 쿼리:

```sql
SELECT ... FROM memory_item
WHERE project_key = ?
ORDER BY embedding <=> CAST(? AS vector(768))
LIMIT ?
```

`<=>`는 pgvector 코사인 거리 연산자. Spring Data JPA의 파생쿼리(`findByXxxOrderBy...`)는 이걸 표현할 수 없다. 또 `vector(768)` 컬럼은 Hibernate 기본 매핑이 없어 엔티티 필드로 직접 read/write 불가.

옵션:

1. **JPA `@Query(nativeQuery=true)`** — 엔티티에 native SQL 박기
2. **Hibernate UserType / Converter** — vector 타입 매핑 만들기
3. **`JdbcTemplate` 분리 리포지터리** — JPA 우회

UserType 만드는 비용 vs 활용도 검토했을 때, v1엔 ANN 인덱스(IVFFlat/HNSW)도 없는 상황이라 vector 컬럼 read/write가 INSERT + SELECT 두 군데뿐. 비용 회수 안 됨.

## Decision

**`MemoryJdbcRepository`(`JdbcTemplate` 기반)에 native SQL 두 메서드를 둔다:**

- `insert(...)` — `INSERT ... VALUES (..., CAST(? AS vector(768)), ...) RETURNING id, created_at`
- `search(projectKey, queryVec, k)` — `ORDER BY embedding <=> CAST(? AS vector(768)) LIMIT ?`

JPA 엔티티 `MemoryItem`은 메타데이터(text/source/createdAt/...)만 매핑하고, `embedding` 필드는 `@Transient`로 두어 Hibernate가 손대지 않게 한다. `ddl-auto=validate`는 엔티티가 선언한 컬럼이 DB에 존재하는지만 보고, DB의 추가 컬럼은 무시한다.

벡터 리터럴 포맷팅(`[v1,v2,...]`)은 `MemoryJdbcRepository.toVectorLiteral()` 헬퍼에서 `Locale.ROOT`로 점(.) 소수점을 고정한다.

## Consequences

긍정:
- 벡터 컬럼을 표현력 있게 다룸 — `<=>`, `<#>`, `<->` 등 연산자 모두 사용 가능
- 향후 인덱스 도입(IVFFlat/HNSW) 시에도 native가 더 자연스러움
- JPA 자동 매핑의 함정(N+1, dirty checking 오버헤드 등) 회피

부정:
- 쓰기/읽기가 JPA 영속성 컨텍스트 밖에서 일어남 — `@Transactional` 안에서 호출하면 트랜잭션 경계는 유지되지만 1차 캐시 효과 없음
- 엔티티 + Jdbc 리포지터리 두 클래스로 분리 — 단순 CRUD 대비 코드량 약간 늘어남
- vector 리터럴 문자열 포맷팅 책임이 호출자에게 — 잘못 인코딩하면 런타임 에러

비고:
- v2에서 ANN 인덱스 도입 시: `CREATE INDEX ... USING ivfflat (embedding vector_cosine_ops)`. native SQL 그대로 사용 + EXPLAIN으로 인덱스 사용 확인.
