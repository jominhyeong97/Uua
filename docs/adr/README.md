# Architecture Decision Records

v1 동안 한 핵심 결정의 기록. Michael Nygard 포맷 (간결판):

- **Context** — 왜 결정해야 했나
- **Decision** — 무엇을 골랐나
- **Consequences** — 그래서 무엇이 따라오나

| # | 제목 | 핵심 |
|---|---|---|
| [001](001-approach-a.md) | Approach A 선택 → B 확장 인터페이스 | 좁고 깊게 출시, B 조각이 끼워질 자리만 비워둠 |
| [002](002-native-sql-for-vector-search.md) | 벡터검색은 네이티브 SQL | JPA 파생쿼리로 `<=>` 표현 불가, JdbcTemplate 직접 |
| [003](003-ingest-partial-commit.md) | Ingest 부분 커밋 | 청크 1개당 트랜잭션. 중간 실패해도 i-1개는 보존 |
| [004](004-usage-guard-decorator.md) | Usage 가드는 데코레이터 | Gemini 클라이언트 위에 한 겹. 호출자 코드 변경 0 |
| [005](005-embedding-model-migration.md) | `text-embedding-004` → `gemini-embedding-001` | 모델명 변경. 768차원 동일 → 스키마 영향 없음 |
| [006](006-size-413-disambiguation.md) | Size 위반 413 분기는 max 속성으로 | `text` 필드 중복(8000자 vs 200KB) 충돌 해결 |
