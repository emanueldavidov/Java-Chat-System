# Stage 1: Build Stage - Using a full Maven image to compile the application
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# A. Copy only the pom.xml and download dependencies. 
# This layer is cached unless pom.xml changes, speeding up subsequent builds.
COPY pom.xml .
RUN mvn dependency:go-offline -B

# B. Copy the source code and build the application.
# Tests are skipped to speed up the container creation process.
COPY src ./src
RUN mvn package -DskipTests

# Stage 2: Runtime Stage - Using a lightweight JRE image for production
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create a non-root user and group for better security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# C. Copy only the final JAR file from the build stage.
# This keeps the final image size small by excluding Maven and source files.
COPY --from=build /app/target/tcp-chat-1.0-SNAPSHOT.jar app.jar

# D. Create a non-root user to run the application for better security.
RUN chown appuser:appgroup app.jar /app

# Switch to the non-root user to run the application
USER appuser

# Expose the port the chat server listens on
EXPOSE 9999

# Execute the application
# Note: The Main-Class is expected to be defined in the pom.xml manifest
CMD ["java", "-jar", "app.jar"]