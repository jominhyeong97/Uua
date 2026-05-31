package com.uua.memory;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 메타데이터 조회용 JPA 리포지터리(단계 ③ list/get에서 활용).
 * 쓰기와 임베딩 컬럼 read/write는 {@link MemoryJdbcRepository}가 담당한다.
 */
public interface MemoryRepository extends JpaRepository<MemoryItem, Long> {
}
