# 后端镜像：宿主机 mvn package 产出 jar 后使用（Windows 本机开发现实，构建快）
# 用法：mvn package -DskipTests && docker build -t interview-backend .
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/ai-mock-interview-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
