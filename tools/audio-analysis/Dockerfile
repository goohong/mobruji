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

WORKDIR /app

COPY requirements.txt /app/requirements.txt
RUN pip install --upgrade pip \
 && pip install -r requirements.txt

COPY analyze.py /app/analyze.py
COPY test_analyze.py /app/test_analyze.py
COPY README.md /app/README.md

# 비-root 사용자 (yt-dlp 다운로드 시 임시 dir 권한 분리)
RUN useradd --create-home --shell /bin/bash analyzer \
 && chown -R analyzer:analyzer /app
USER analyzer

ENTRYPOINT ["python", "analyze.py"]
CMD ["--help"]
