# 1: Image for building the application JAR file
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /home/gradle/src

# Copy the *entire* project in first
COPY . .

# Run the 'buildFatJar' task explicitly to create the '-all.jar'
RUN chmod +x ./gradlew && ./gradlew buildFatJar -x test --no-daemon

# 2: Image that runs in the server, running the app built above.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy the built JAR from the build stage
COPY --from=build --chown=appuser:appgroup /home/gradle/src/build/libs/*-all.jar ./app.jar

EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]