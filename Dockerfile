# Сборка
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Зависимости отдельным слоем: при правках кода он берётся из кеша
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# Запуск
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S -G app app && mkdir -p /data && chown app:app /data
COPY --from=build /build/target/*.jar app.jar

USER app
VOLUME ["/data"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
