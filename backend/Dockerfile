# Multi-stage build para Spring Boot 4.1.1 + Java 21
# Etapa 1: build com Maven
FROM maven:3.9.12-eclipse-temurin-21-alpine AS build
WORKDIR /app
# Copia apenas arquivos necessários para cache de dependências
COPY pom.xml .
RUN mvn dependency:go-offline -B
# Copia o resto do código fonte
COPY src ./src
RUN mvn package -DskipTests -B

# Etapa 2: runtime com JRE 21 (imagem menor que JDK)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Copia o JAR gerado (nome baseado no artifactId do pom.xml)
COPY --from=build /app/target/sentinel-backend-0.1.0-SNAPSHOT.jar app.jar
# A porta é resolvida no RUNTIME pelo ENTRYPOINT: Render fornece PORT,
# local usa o fallback 8080. ENV server-side seria congelado no build.
ENV SPRING_PROFILES_ACTIVE=prod
# Configuração de memória para Render Free (512 MB limite total)
ENV JAVA_OPTS="-Xms128m -Xmx256m -XX:+UseContainerSupport"
# Usuário não privilegiado (opcional, quando viável)
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
# Expõe a porta (não publica automaticamente, apenas documenta)
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "export SERVER_PORT=${PORT:-8080}; java $JAVA_OPTS -jar app.jar"]
