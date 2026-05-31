package com.uua;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// 단계 ②: EmbeddingProperties 같은 record 기반 @ConfigurationProperties를
// com.uua 하위에서 자동 등록하기 위해 추가.
@SpringBootApplication
@ConfigurationPropertiesScan
public class UuaApplication {

	public static void main(String[] args) {
		SpringApplication.run(UuaApplication.class, args);
	}

}
