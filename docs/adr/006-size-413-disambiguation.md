# 006. `Size` 위반의 413 분기는 `max` 속성으로 좁힌다

Date: 2026-05-31
Status: Accepted

## Context

두 요청 바디에 같은 필드명 `text`가 있다 — 의미가 다르다:

| 엔드포인트 | 필드 | `@Size(max=…)` | 길이 위반 의미 |
|---|---|---|---|
| `POST /api/memories` (단계 ②) | `text` | 8000 | 본문이 한 메모리에 들어가기엔 너무 김 → 413 `text_too_long` |
| `POST /api/context` (단계 ③) | `query` | 8000 | 위와 같음 |
| `POST /api/sessions/{id}/ingest` (단계 ④) | `text` | 200000 | 200KB 덤프 한 번에 보내는 건 의도된 한계지만, 그래도 넘으면 잘못된 요청 → 400 `validation_failed` |

초기 `GlobalExceptionHandler`는 단순했다:

```java
private static final Set<String> LONG_BODY_FIELDS = Set.of("text", "query");

boolean textTooLong = errors.stream().anyMatch(e ->
    LONG_BODY_FIELDS.contains(e.getField()) && "Size".equals(e.getCode()));
```

→ 단계 ④ ingest의 200001자 `text`도 413 응답에 max=8000으로 잘못 매핑됨. 슬라이스 테스트가 잡았다.

## Decision

**`FieldError.getArguments()`에서 max 속성을 직접 읽어 정확히 8000일 때만 413으로 분기.**

```java
private static boolean hasSizeMax(FieldError e, int expected) {
    Object[] args = e.getArguments();
    if (args == null) return false;
    for (Object a : args) {
        if (a instanceof Integer i && i == expected) return true;
    }
    return false;
}

boolean textTooLong = errors.stream().anyMatch(e ->
    LONG_BODY_FIELDS.contains(e.getField())
        && "Size".equals(e.getCode())
        && hasSizeMax(e, TEXT_MAX));
```

`SpringValidatorAdapter`가 `@Size` 위반을 `FieldError`로 변환할 때 인자 배열에 (필드명 리졸버블) + 정렬된 어노테이션 속성(max, min)을 담는다. 순서에 의존하지 않고 "Integer가 expected 값과 같은 게 하나라도 있는지" 검사 — `@Size`에서 max/min 둘 다 Integer라 매처가 robust.

## Consequences

긍정:
- 413 의미가 정확해짐 — "8000자 본문 한계 초과"만 trigger
- ingest의 큰 본문(200KB)은 fallthrough로 400 `validation_failed` 응답 — 클라이언트가 "이건 다른 종류의 에러"로 분기 가능
- 새로운 8000자 본문 필드가 생기면 `LONG_BODY_FIELDS` 한 줄만 추가 (현재 `{"text", "query"}`)

부정:
- 매처가 매직 넘버(`TEXT_MAX = 8000`)에 의존 — 향후 한계를 바꾸면 한 군데(`GlobalExceptionHandler` 상수) 손대면 됨, 그런데 그게 비즈니스 한계라 흩어지면 일관성 깨질 위험
- Spring 내부 구현(`getArguments()` 인자 순서/내용)에 살짝 의존 — 다행히 "Integer 8000 있나"는 순서 무관 검사라 깨질 가능성 낮음

비고:
- 향후 필드별 max가 모두 다르면(예: 1000, 5000, 8000) 매처가 복잡해짐. 그땐 필드명 → 한계 매핑 테이블이 정답
