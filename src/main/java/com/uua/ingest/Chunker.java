package com.uua.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * 고정창 청킹(DESIGN.md "v1 구체 스펙"):
 * - 윈도우: ~500 토큰 ≒ 2000자 (단계 ②의 token ≈ chars/4 근사와 일치)
 * - 오버랩 없음
 * - 자투리(마지막 청크)는 작아도 그대로 보존
 *
 * UTF-16 코드유닛 단위로 자른다 — 한글도 BMP에 있어 1글자 = 1 코드유닛.
 * 이모지/서로게이트 페어가 잘리는 코너케이스가 v1엔 거의 없고, 잘려도 임베딩 호출엔 영향 없음.
 */
public final class Chunker {

    public static final int DEFAULT_WINDOW_CHARS = 2000;

    private Chunker() {}

    public static List<String> chunk(String text) {
        return chunk(text, DEFAULT_WINDOW_CHARS);
    }

    public static List<String> chunk(String text, int windowChars) {
        if (windowChars <= 0) {
            throw new IllegalArgumentException("windowChars must be > 0");
        }
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int n = text.length();
        List<String> out = new ArrayList<>((n + windowChars - 1) / windowChars);
        for (int i = 0; i < n; i += windowChars) {
            int end = Math.min(i + windowChars, n);
            out.add(text.substring(i, end));
        }
        return out;
    }
}
