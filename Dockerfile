# syntax=docker/dockerfile:1
# Uua 단계 ① 배포용 이미지 (Render 무료 웹서비스 = RAM 512MB 가정).

# --- 1) 빌드 단계: JDK로 bootJar 생성 ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# 의존성 캐시 활용: 빌드 스크립트/래퍼 먼저 복사
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true
# 소스 복사 후 실행 가능한 jar 빌드 (테스트는 CI에서, 이미지 빌드시간 단축 위해 제외)
COPY src ./src
RUN ./gradlew clean bootJar -x test --no-daemon

# --- 2) 실행 단계: 가벼운 JRE ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# RAM 512MB 환경 OOM 방지:
#  - MaxRAMPercentage=60 → 힙 상한을 컨테이너 메모리의 60%로 (나머지는 metaspace/스레드/off-heap 여유)
#  - SerialGC → 단일 코어/저메모리에서 G1보다 메모리 오버헤드 적음
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60.0 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
