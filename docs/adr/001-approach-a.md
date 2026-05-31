# 001. Approach A 선택 → B 확장 인터페이스 미리 갈라두기

Date: 2026-05-26
Status: Accepted

## Context

설계 단계에서 3가지 접근을 검토했다 (DESIGN.md "Approaches Considered" 참조):

- **A (Thin Slice)**: 텍스트 청크 → Gemini 임베딩 → pgvector 저장. 의미검색 + 최신성 부스트 + 토큰버짓 그리디로 컨텍스트 팩. REST + MCP. Effort S/M, Risk Low.
- **B (Full Memory Engine)**: LLM 기반 typed memory 추출(fact/decision/entity/todo) + dedup + 모순감지 + supersedes 체인. Effort L/XL, Risk High.
- **C (Event-Sourced)**: 이벤트 로그 + projection(CQRS). Effort M/L, Risk Med.

제약:
- 1인 + 주니어 + 구직 타임라인 (빠른 데모 우선)
- 무료티어 고정 ($0 유지)
- 매일 실사용 가능한 상태가 1순위 (성공기준 ⓐ)

B의 "타입드 메모리·모순감지"는 매력적이지만 연구급 난제. C의 이벤트 소싱은 아키텍처 스토리는 강하나 실사용 거리 + 오버엔지니어링 리스크.

## Decision

**A를 v1로 출시한다.** 단, B의 조각(타입드 메모리·supersedes 체인)이 추후 끼워질 수 있게 인터페이스를 미리 갈라둔다:

- `MemoryItem` 엔티티에 `supersedesId BIGINT REFERENCES memory_item(id)` 컬럼만 자리 잡음 (v1 미사용, V1 마이그레이션에 포함)
- `EmbeddingClient` / (예약된) `LlmClient` 인터페이스 분리 — v1엔 임베딩만 의존, v2 추출/요약은 LlmClient로 교체

## Consequences

긍정:
- 단계 ①~⑥을 ~2주 안에 출시 + 라이브 검증 + 측정 가능
- "내가 매일 쓰는 + 배포된 + 측정된" 상태가 면접 자산이 됨
- B로 확장 시 스키마 마이그레이션 불필요 (자리 미리 있음)

부정:
- v1엔 typed memory 없음 — 모든 메모리가 동일한 `MemoryItem`
- 모순감지 없음 — 같은 주제의 상반된 메모가 둘 다 검색됨 (랭킹에서 최신 우선이지만 둘 다 노출)

비고:
- B의 "LLM 기반 추출"은 v1엔 비용 + 복잡도 vs 가치가 안 맞음. dogfood 데이터가 쌓이면 v2에서 재평가.
