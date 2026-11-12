# Build context: repository root. Native SDK and adapter run in the same JVM.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope -am package -DskipTests \
    && mkdir /out && find managed-agents/agent-harness-runtime/java/brain-agentscope/target -maxdepth 1 -name 'managed-agents-agent-harness-runtime-*.jar' ! -name '*sources*' ! -name '*javadoc*' -exec cp {} /out/app.jar \;
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd --gid 65532 managed-agents && useradd --uid 65532 --gid 65532 --home-dir /data --no-create-home managed-agents \
    && mkdir -p /data/workspaces /data/managed-runtime-blobs && chown -R 65532:65532 /data
COPY --from=build /out/app.jar /app.jar
USER 65532:65532
EXPOSE 8082
ENTRYPOINT ["java","-jar","/app.jar"]
