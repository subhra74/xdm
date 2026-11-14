# HLS packagers the sites users download from are built on, to make MPEG-TS segments the way they do
# (see private-docs/docs/transmuxer-ffmpeg-parity.md, "Where the streams come from"):
#   nginx + nginx-vod-module  TS segments cut per request from MP4 files (tube sites)
#   GPAC (gpac / MP4Box)      dasher with TS segments
#   GStreamer                 hlssink2 (mpegtsmux)
#   Bento4 (mp42hls)          MP4 -> HLS TS
#   ffmpeg                    hls muxer, for comparison inside the same image
# Build: docker build -t xdm-packagers tools/ffparity/docker      Use: packagers.sh
FROM debian:trixie-slim

ARG NGINX_VERSION=1.26.3
# Kaltura's own repository is gone; its last upstream commit (erankor, 2024-01-28) is built from a mirror
# that kept the history. A commit id covers the whole tree, so this is Kaltura's code, not the mirror's later changes.
ARG VOD_COMMIT=5b96c97213a0bdd69cde6bcaa5bff6b7b75a61ca
ARG VOD_REPO=https://github.com/dio-az/nginx-vod-module.git
ARG BENTO4_VERSION=1.6.0-641
ARG GPAC_VERSION=2.4.0

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl build-essential cmake git python3 \
        libpcre2-dev zlib1g-dev libssl-dev \
        pkg-config ffmpeg \
        gstreamer1.0-tools gstreamer1.0-plugins-base gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
    && rm -rf /var/lib/apt/lists/*

# GPAC (gpac, MP4Box) from its release tag: Debian trixie has no gpac package.
RUN git clone --depth 1 --branch v${GPAC_VERSION} https://github.com/gpac/gpac.git /tmp/gpac \
    && cd /tmp/gpac && ./configure --prefix=/usr/local && make -j"$(nproc)" && make install \
    && ldconfig && rm -rf /tmp/gpac

# Bento4 (mp42hls and friends), from its release tag.
RUN git clone --depth 1 --branch v${BENTO4_VERSION} https://github.com/axiomatic-systems/Bento4.git /tmp/bento4 \
    && cmake -S /tmp/bento4 -B /tmp/bento4/build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build /tmp/bento4/build -j"$(nproc)" --target mp42hls mp4info mp4dump \
    && cp /tmp/bento4/build/mp42hls /tmp/bento4/build/mp4info /tmp/bento4/build/mp4dump /usr/local/bin/ \
    && rm -rf /tmp/bento4

# nginx with nginx-vod-module (Kaltura), local (file) mode.
RUN curl -fsSL https://nginx.org/download/nginx-${NGINX_VERSION}.tar.gz | tar -xz -C /tmp \
    && git init -q /tmp/nginx-vod-module && cd /tmp/nginx-vod-module \
    && git fetch -q --depth 1 ${VOD_REPO} ${VOD_COMMIT} && git checkout -q FETCH_HEAD \
    && test "$(git rev-parse HEAD)" = "${VOD_COMMIT}" \
    && cd /tmp/nginx-${NGINX_VERSION} \
    && ./configure --prefix=/opt/nginx --with-threads --with-file-aio --add-module=/tmp/nginx-vod-module \
         --with-cc-opt="-O2 -Wno-error" \
    && make -j"$(nproc)" && make install \
    && rm -rf /tmp/nginx-${NGINX_VERSION} /tmp/nginx-vod-module

COPY nginx.conf /opt/nginx/conf/nginx.conf

# GStreamer's decoders, for playing outputs the way Linux desktop players (GNOME Videos/Showtime,
# Totem) do: qtdemux + decodebin. A layer of its own so the builds above stay cached.
RUN apt-get update && apt-get install -y --no-install-recommends gstreamer1.0-libav \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /work
