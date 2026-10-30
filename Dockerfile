# 빌드: Gradle로 실행 가능한 jar를 만든다 (테스트는 CI/로컬에서 돌리므로 이미지 빌드에서는 건너뛴다).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src src
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/*-SNAPSHOT.jar app.jar

# 실행: JRE만 담고 root가 아닌 사용자로 돈다.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
# 컨테이너 메모리에 맞춰 힙을 잡는다. Railway가 넣는 PORT는 application.yml의 server.port가 읽는다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
