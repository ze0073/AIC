ARG BASE_IMAGE=eclipse-temurin:21-jre
FROM ${BASE_IMAGE}
WORKDIR /app
COPY target/campus-registration-demo-0.6.1-SNAPSHOT.jar app.jar
EXPOSE 8091
ENTRYPOINT ["java","-jar","/app/app.jar"]
