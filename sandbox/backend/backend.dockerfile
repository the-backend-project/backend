# Adds the JDK and Maven to an agent's sandbox. The tools are unpacked in a build stage and copied into an empty stage,
# so the kit adds only them and leaves the agent's image as it is.
FROM debian:trixie-slim AS build
ARG TARGETARCH
# The JDK is Temurin, as in CI. Maven is the latest 3.9 release.
ARG JDK_RELEASE=25.0.5.1+2
ARG JDK_SHA256_ARM64=c37879e0d08eeb1baa69fcbb7758cd455b7b1c0ceab1e2a136ccb1713c530984
ARG JDK_SHA256_AMD64=760f91b22c3efc9bd1b64ff950598231be82928cc807e30691a0e7a2995a62c0
ARG MAVEN_VERSION=3.9.16
ARG MAVEN_SHA512=831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates
RUN case "$TARGETARCH" in \
      arm64) arch=aarch64 sha256=$JDK_SHA256_ARM64 ;; \
      amd64) arch=x64 sha256=$JDK_SHA256_AMD64 ;; \
      *) echo "unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac \
    && version=$(echo "$JDK_RELEASE" | tr + _) \
    && release=$(echo "jdk-$JDK_RELEASE" | sed 's/+/%2B/') \
    && curl -fsSL -o jdk.tar.gz \
       "https://github.com/adoptium/temurin25-binaries/releases/download/$release/OpenJDK25U-jdk_${arch}_linux_hotspot_$version.tar.gz" \
    && echo "$sha256  jdk.tar.gz" | sha256sum -c - \
    && mkdir -p /out/opt/jdk \
    && tar -xzf jdk.tar.gz -C /out/opt/jdk --strip-components=1
RUN curl -fsSL -o maven.tar.gz \
      "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz" \
    && echo "$MAVEN_SHA512  maven.tar.gz" | sha512sum -c - \
    && mkdir -p /out/opt/maven \
    && tar -xzf maven.tar.gz -C /out/opt/maven --strip-components=1
# Links in /usr/local/bin put the tools on the PATH of every agent's image.
RUN mkdir -p /out/usr/local/bin \
    && for tool in /out/opt/jdk/bin/*; do ln -s "/opt/jdk/bin/$(basename "$tool")" /out/usr/local/bin/; done \
    && ln -s /opt/maven/bin/mvn /out/usr/local/bin/mvn

FROM scratch
COPY --from=build /out/ /
