# Stage 1: Build the application JAR file
FROM gradle:8.8.0-jdk17 AS build
WORKDIR /home/gradle/src

# Copy the *entire* project in first
COPY . .

# Run the 'buildFatJar' task explicitly to create the '-all.jar'
# This is the task the Ktor plugin provides.
RUN ./gradlew buildFatJar -x test

# Stage 2: Create the final production image
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built JAR from the build stage
COPY --from=build /home/gradle/src/build/libs/*-all.jar ./app.jar

EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]