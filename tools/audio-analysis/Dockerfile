# Python audio analysis worker (spec: docs/features/audio-tooling-bootstrap.md)
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

# ffmpeg: yt-dlp 후처리 (mp3 추출 + clip 길이 자르기)
# libsndfile1: librosa/soundfile audio IO 의존성
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ffmpeg \
      libsndfile1 \
 && rm -rf /var/lib/apt/lists/*

# Deno JS 런타임 (#1826): yt-dlp 가 YouTube nsig/sig JS 챌린지를 풀려면 JS 인터프리터가
# PATH 에 있어야 한다. 없으면 yt-dlp 의 JS Challenge Provider 가 전부 unavailable 이라
# 시그니처 미해결로 'Video unavailable' 이 된다 — bgutil PO token(#1813)만으로는 부족.
# node 대비 단일 정적 바이너리라 의존성이 가벼워 deno 를 채택. bin 이미지에서 복사해 핀 고정.
COPY --from=denoland/deno:bin-2.1.4 /deno /usr/local/bin/deno

WORKDIR /app

# 기본은 경량 경로(yt-dlp+librosa). vocal stem 분리(Spleeter/TensorFlow)는
# --build-arg WITH_VOCAL_SEPARATION=true 로만 설치한다 (spec §10-2 CI/이미지 무게).
ARG WITH_VOCAL_SEPARATION=false

COPY requirements.txt /app/requirements.txt
COPY requirements-vocal.txt /app/requirements-vocal.txt
RUN pip install --upgrade pip \
 && pip install -r requirements.txt \
 && if [ "$WITH_VOCAL_SEPARATION" = "true" ]; then \
      pip install -r requirements-vocal.txt; \
    fi

COPY analyze.py /app/analyze.py
COPY test_analyze.py /app/test_analyze.py
COPY README.md /app/README.md

# 비-root 사용자 (yt-dlp 다운로드 시 임시 dir 권한 분리)
RUN useradd --create-home --shell /bin/bash analyzer \
 && chown -R analyzer:analyzer /app
USER analyzer

ENTRYPOINT ["python", "analyze.py"]
CMD ["--help"]
