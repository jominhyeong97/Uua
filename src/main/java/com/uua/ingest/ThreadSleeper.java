package com.uua.ingest;

import org.springframework.stereotype.Component;

/**
 * 운영용 {@link Sleeper} 구현. millis<=0이면 sleep 호출조차 하지 않는다.
 * 인터럽트는 그대로 전파하지 않고 플래그만 복구 — ingest 루프가 다음 청크에서 자연 종료되게.
 */
@Component
public class ThreadSleeper implements Sleeper {
    @Override
    public void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
