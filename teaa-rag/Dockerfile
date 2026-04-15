FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY target/teaa-rag-*.jar app.jar

RUN mkdir -p /app/rag-data

ENV RAG_DATA_PATH=/app/rag-data

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
