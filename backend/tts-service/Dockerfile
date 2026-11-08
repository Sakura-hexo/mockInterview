FROM python:3.12-slim

# 大陆构建网络换清华 pypi 镜像源（与 asr 边车同法）
RUN pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple \
    'sherpa-onnx>=1.12' numpy

# 模型构建期烘入：权重文件放本目录后随上下文 COPY（gitignore 不入库）。
# 实测 Docker VM 直连 hf-mirror 仅约 9KB/s，走宿主机下载再 COPY 才现实。
# 获取方式（宿主机实测 hf-mirror 约 4MB/s，文件同名放本目录）：
#   https://hf-mirror.com/csukuangfj/matcha-icefall-zh-en/resolve/main/model-steps-3.onnx
#   https://hf-mirror.com/csukuangfj/matcha-icefall-zh-en/resolve/main/lexicon.txt
#   https://hf-mirror.com/csukuangfj/matcha-icefall-zh-en/resolve/main/tokens.txt
#   https://hf-mirror.com/csukuangfj/matcha-icefall-zh-en/resolve/main/date-zh.fst
#   https://hf-mirror.com/csukuangfj/matcha-icefall-zh-en/resolve/main/number-zh.fst
#   https://hf-mirror.com/csukuangfj/matcha-icefall-zh-en/resolve/main/phone-zh.fst
#   espeak-ng-data 为目录（约 18MB，英文音素化用），整目录拷入本目录
# 声码器（16kHz 专用，配错采样率会变速变调）：
#   https://hf-mirror.com/csukuangfj/sherpa-onnx-vocoders/resolve/main/vocos-16khz-univ.onnx
RUN mkdir -p /opt/model/matcha-zh-en
COPY model-steps-3.onnx lexicon.txt tokens.txt \
     date-zh.fst number-zh.fst phone-zh.fst vocos-16khz-univ.onnx \
     /opt/model/matcha-zh-en/
COPY espeak-ng-data /opt/model/matcha-zh-en/espeak-ng-data

COPY server.py /app/server.py

EXPOSE 8000
CMD ["python", "/app/server.py"]
