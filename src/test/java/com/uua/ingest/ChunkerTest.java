package com.uua.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chunker는 단순하지만 경계 조건이 많다 — 0자, 윈도우와 딱 같음, 1초과, 정확한 배수, null.
 */
class ChunkerTest {

    @Test
    void 빈_또는_null이면_빈_리스트() {
        assertThat(Chunker.chunk("", 100)).isEmpty();
        assertThat(Chunker.chunk(null, 100)).isEmpty();
    }

    @Test
    void 윈도우보다_짧으면_청크_1개() {
        List<String> chunks = Chunker.chunk("hello", 100);
        assertThat(chunks).containsExactly("hello");
    }

    @Test
    void 윈도우와_같으면_청크_1개() {
        String s = "a".repeat(100);
        assertThat(Chunker.chunk(s, 100)).containsExactly(s);
    }

    @Test
    void 윈도우_1초과이면_2개로_나뉘고_자투리는_그대로_보존() {
        // 101자 + 윈도우 100 → [0..100], [100..101]
        String s = "a".repeat(101);
        List<String> chunks = Chunker.chunk(s, 100);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(100);
        assertThat(chunks.get(1)).hasSize(1);
    }

    @Test
    void 윈도우의_정확한_배수면_자투리_없이_나뉜다() {
        // 4000자 + 윈도우 2000 → 2 청크, 각 2000자
        String s = "x".repeat(4000);
        List<String> chunks = Chunker.chunk(s, 2000);
        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(c -> assertThat(c).hasSize(2000));
    }

    @Test
    void 한글도_글자_단위로_잘라_길이가_맞는다() {
        // 윈도우 5에 한글 12자 → 5+5+2
        String s = "가나다라마바사아자차카타";
        List<String> chunks = Chunker.chunk(s, 5);
        assertThat(chunks).containsExactly("가나다라마", "바사아자차", "카타");
    }

    @Test
    void 윈도우_0이하이면_예외() {
        assertThatThrownBy(() -> Chunker.chunk("hello", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Chunker.chunk("hello", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
