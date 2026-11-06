FROM python:3.12-slim
# >=7.3.1 起为 v2 鉴权（v1 已被微软 403，2026-11-06 实测）；协议再变只动此约束
RUN pip install --no-cache-dir 'edge-tts>=7.3.1'
COPY server.py /app/server.py
EXPOSE 8000
CMD ["python", "/app/server.py"]
