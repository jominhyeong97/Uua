package com.uua.ingest;

/**
 * Thread.sleep 추상화 — 테스트가 실제로 잠들지 않도록 한다.
 * 운영 빈은 {@link ThreadSleeper}이고, 테스트에선 호출 횟수만 기록하는 no-op로 교체.
 */
public interface Sleeper {
    void sleep(long millis);
}
