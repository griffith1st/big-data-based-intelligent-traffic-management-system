# ===================================================
# ITMS-BD ��˷��� Dockerfile
# ����Java 17, Spring Boot 3.2
# ===================================================

# �����׶�
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -U

# ���н׶�
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# ����ʱ��
RUN apk add --no-cache tzdata
ENV TZ=Asia/Shanghai

# ���ƹ�������
COPY --from=builder /build/target/*.jar app.jar

# ��¶�˿�
EXPOSE 8080

# �������
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# ��������
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.profiles.active=prod"]
