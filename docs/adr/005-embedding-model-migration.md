# 005. `text-embedding-004` → `gemini-embedding-001` 모델 마이그레이션

Date: 2026-05-26
Status: Accepted

## Context

설계 SSOT(`docs/DESIGN.md`, 2026-05-26 APPROVED)는 임베딩 모델로 Gemini `text-embedding-004`를 명시했다. 단계 ② 구현 시점에 Google 공식 문서를 확인했더니:

- `text-embedding-004`는 deprecation 트랙
- **`gemini-embedding-001`**가 새 무료 표준 (RPD ~1500)
- 차원: `output_dimensionality=768` 명시 가능 → 기존 `vector(768)` 스키마 그대로

추가로 무료티어 약관 변경:
- 무료티어 입력은 Google 학습 데이터로 사용될 수 있음 (프라이버시 트레이드오프)
- 결제계정 미연결 시에도 RPD 한도 유지

문제:
- DESIGN.md를 수정하면 SSOT의 "approved on date X" 의미가 흐려짐
- Flyway V1__init.sql 주석에 옛 모델명이 적혀있는데, 적용 후엔 checksum mismatch로 수정 불가 (Flyway가 부팅 거부)

## Decision

**코드는 `gemini-embedding-001` 사용. 차원은 `output_dimensionality=768`로 명시.**

- `application.properties`의 `gemini.model=${GEMINI_MODEL:gemini-embedding-001}` 기본값으로 설정
- `EmbeddingProperties` Javadoc에 모델 변경 노트 + Flyway checksum 함정 명시
- V1__init.sql 주석은 옛 이름 그대로 보존(수정 시 부팅 실패). 새 마이그레이션이 필요한 변화는 없음 — 차원 동일.
- DESIGN.md는 SSOT 보존 차원에서 그대로 두고, HANDOFF.md에 변경 사실 + 이유 기록

## Consequences

긍정:
- 무료티어 그대로 사용 (성공기준 ⓑ "비용 0" 유지)
- `vector(768)` 스키마 영향 없음 — 마이그레이션 없이 모델 교체
- 환경변수 `GEMINI_MODEL`로 런타임 오버라이드 가능 (v2 모델 비교 실험 시 유리)

부정:
- 설계 문서와 코드 사이에 모델명 불일치 — Javadoc 노트 + 이 ADR로 추적
- Flyway V1__init.sql 주석의 옛 이름을 못 고침 — 처음 코드 보는 사람에게 혼란

비고:
- 추후 무료티어 정책 변경 또는 새 모델 등장 시 같은 패턴으로 마이그레이션 + ADR 추가
- 차원이 바뀌는 모델로 갈 경우 새 Flyway 마이그레이션 필요 (`ALTER TABLE memory_item ALTER COLUMN embedding TYPE vector(N) USING NULL` + 전체 재임베딩)
