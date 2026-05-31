package com.uua;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

// 단계 ②: EmbeddingProperties 같은 record 기반 @ConfigurationProperties를
// com.uua 하위에서 자동 등록하기 위해 추가.
@SpringBootApplication
@ConfigurationPropertiesScan
public class UuaApplication {

	public static void main(String[] args) {
		SpringApplication.run(UuaApplication.class, args);
	}

	// 단계 ③: ContextService가 "now"를 Clock에서 받아 recency 점수 계산.
	// 테스트는 @MockitoBean 또는 fixed Clock으로 재정의해 시간을 고정.
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

}
