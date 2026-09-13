# Use a lightweight Java image
FROM eclipse-temurin:21-jdk-alpine

# Set the working directory inside the container
WORKDIR /app

# Copy all your project files into the container
COPY . .

# Build the project using your script
RUN sh build.sh

# Expose port 8080 (so we can connect from outside)
EXPOSE 8081

# Command to run the server when container starts
CMD ["java", "-cp", "out", "AtomicKVServer", "8081"]
