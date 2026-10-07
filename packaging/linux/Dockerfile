# Build image for the Linux bundles and packages (packaging/build-linux.sh, PACKAGING.md section 7).
#
# One image per architecture: build it with --platform linux/arm64 or linux/amd64. Ubuntu 22.04 is the
# oldest release supported, so the packaging tools are the oldest the packages have to work with.
# The JDK is the JetBrains Runtime jbrsdk (its java.desktop has the native Wayland toolkit, section 7.6),
# and nfpm makes the deb, rpm and Arch packages. Both downloads are checked against their checksums.
FROM ubuntu:22.04

ARG TARGETARCH
ARG JBR_VERSION=25.0.4.1
ARG JBR_BUILD=b635.70
ARG NFPM_VERSION=2.47.0

ENV DEBIAN_FRONTEND=noninteractive
# binutils: objcopy, for jlink --strip-native-debug-symbols. zip: build-bundle.sh strips foreign natives.
# The X11/Wayland/font/sound libraries are what the linked runtime links against: installed so the
# AppCDS dump runs and ldd can check the packages' dependency lists.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl binutils zip unzip xz-utils file \
      libx11-6 libxext6 libxrender1 libxtst6 libxi6 libfreetype6 fontconfig fonts-dejavu-core \
      libasound2 libwayland-client0 libwayland-cursor0 libxkbcommon0 libgtk-3-0 \
 && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    case "$TARGETARCH" in \
      arm64) jbr_arch=aarch64; nfpm_arch=arm64 ;; \
      amd64) jbr_arch=x64; nfpm_arch=x86_64 ;; \
      *) echo "unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    cd /tmp; \
    jbr="jbrsdk-${JBR_VERSION}-linux-${jbr_arch}-${JBR_BUILD}.tar.gz"; \
    curl -fsSLO "https://cache-redirector.jetbrains.com/intellij-jbr/${jbr}"; \
    curl -fsSL "https://cache-redirector.jetbrains.com/intellij-jbr/${jbr}.checksum" | sha512sum -c -; \
    mkdir -p /opt/jbr; tar -xzf "$jbr" -C /opt/jbr --strip-components=1; rm "$jbr"; \
    nfpm="nfpm_${NFPM_VERSION}_Linux_${nfpm_arch}.tar.gz"; \
    curl -fsSLO "https://github.com/goreleaser/nfpm/releases/download/v${NFPM_VERSION}/${nfpm}"; \
    curl -fsSL "https://github.com/goreleaser/nfpm/releases/download/v${NFPM_VERSION}/checksums.txt" \
      | grep "  ${nfpm}\$" | sha256sum -c -; \
    tar -xzf "$nfpm" -C /usr/local/bin nfpm; rm "$nfpm"; \
    /opt/jbr/bin/java -version; nfpm --version

ENV JAVA_HOME=/opt/jbr
ENV PATH=/opt/jbr/bin:$PATH
WORKDIR /src
