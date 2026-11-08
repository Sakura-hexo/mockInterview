FROM python:3.12-slim

# 大陆构建网络换清华镜像源：官方 deb/pypi 源直连易失败（apt 退出码 100）
RUN sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg bzip2 \
    && rm -rf /var/lib/apt/lists/*
# ffmpeg：webm/opus 等浏览器录音格式转 wav 16k 单声道；bzip2：备选 GitHub tar 包解包用

RUN pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple \
    'sherpa-onnx>=1.12' numpy

# 模型构建期烘入：权重文件放本目录后随上下文 COPY（gitignore 不入库）。
# 实测 Docker VM 直连 hf-mirror 仅约 9KB/s，走宿主机下载再 COPY 才现实。
# 获取方式（任选其一，文件同名放本目录）：
#   1) hf-mirror 国内直链（宿主机实测约 4MB/s）：
#      https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx
#      https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt
#   2) GitHub Release tar 包（需代理畅通）：
#      https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2
# silero_vad.onnx（643KB，长音频静音分段用）：
#      https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
RUN mkdir -p /opt/model/sense-voice
COPY model.int8.onnx tokens.txt silero_vad.onnx /opt/model/sense-voice/

COPY server.py /app/server.py

EXPOSE 8001

CMD ["python", "/app/server.py"]
