# שלב 1: בנייה (Build Stage) - משתמשים באימג' כבד עם Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# א. מעתיקים רק את ה-pom ומורידים ספריות (זה השלב שנשמר במטמון)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# ב. מעתיקים את הקוד ומקמפלים (זה ירוץ מהר אם רק שינית קוד)
COPY src ./src
RUN mvn package -DskipTests

# שלב 2: הרצה (Run Stage) - משתמשים באימג' קליל רק עם JRE
FROM eclipse-temurin:21-jre
WORKDIR /app

# ג. מעתיקים רק את ה-JAR המוכן מהשלב הקודם
COPY --from=build /app/target/tcp-chat-1.0-SNAPSHOT.jar app.jar

EXPOSE 9999
# הרצה רגילה של ה-JAR (כי ב-pom הגדרנו שה-Main הוא Server)
CMD ["java", "-jar", "app.jar"]