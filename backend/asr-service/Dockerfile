FROM python:3.12-slim

# 大陆构建网络换清华镜像源：官方 deb/pypi 源直连易失败（apt 退出码 100）
RUN sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg bzip2 \
    && rm -rf /var/lib/apt/lists/*
# ffmpeg：webm/opus 等浏览器录音格式转 wav 16k 单声道；bzip2：解包模型

RUN pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple \
    'sherpa-onnx>=1.12' numpy

# 模型构建期烘入（78MB int8，GitHub Release 直链）：运行零下载零外联
RUN python -c "import urllib.request; urllib.request.urlretrieve('https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2', '/tmp/model.tar.bz2')" \
    && mkdir -p /opt/model \
    && tar -xjf /tmp/model.tar.bz2 -C /opt/model \
    && rm /tmp/model.tar.bz2

COPY server.py /app/server.py

EXPOSE 8001

CMD ["python", "/app/server.py"]
