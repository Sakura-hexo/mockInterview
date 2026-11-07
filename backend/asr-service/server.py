"""ASR HTTP 边车：Java 后端经此调用本地 SenseVoice 模型做中英日韩粤语音识别。

独立容器与 edge-tts 同构：推理依赖（sherpa-onnx + ffmpeg）与 239MB int8 模型全部
烘在镜像内，运行零外联、零密钥——是浏览器 Web Speech 出墙依赖的永久替代。
模型构建期烘入（构建上下文 COPY，来源链接见 Dockerfile 注释），运行时不下载。
换装自 paraformer-small：实测真机麦克风信噪比下小模型会把技术短语残化成
"计学学"类音节残文，SenseVoice 同条件几乎全对且自带标点（方案见 .agentdocs）。
长音频按静音 VAD 分段后逐段解码：SenseVoice 为短音频设计，实测超约 1 分钟
单口解码标点消失、错字暴增，分段后全程保持质量（实现记录见 .agentdocs）。
"""

import json
import os
import re
import subprocess
import tempfile
import threading
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
import sherpa_onnx

PORT = 8001
MAX_BYTES = 10 * 1024 * 1024
# 前端单段 120 秒封段；余量兜住切后台标签页时定时器被降频导致的自动停漂移
MAX_SECONDS = 180
# 不足此时长单口整段解码（实测 42 秒内质量最佳且零边界风险），超过才启用 VAD 分段
MAX_SINGLE_SHOT_SECONDS = 45
MODEL_DIR = "/opt/model/sense-voice"
# SenseVoice 输出带 <|zh|><|NEUTRAL|> 等事件标记，落库前剥除
TAG_RE = re.compile(r"<\|[^|]*\|>")


class BadAudio(Exception):
    """输入不是可解码的音频（ffmpeg 探测失败）——按客户端错误处理。"""


class TooLong(Exception):
    """音频时长超过上限——按客户端错误处理。"""


RECOGNIZER = sherpa_onnx.OfflineRecognizer.from_sense_voice(
    model=MODEL_DIR + "/model.int8.onnx",
    tokens=MODEL_DIR + "/tokens.txt",
    use_itn=True,
)
# onnxruntime 会话并发解码不安全；单次请求 0.1 秒级，串行无压力
DECODE_LOCK = threading.Lock()

# silero VAD：按静音把长音频切成语音段，规避 SenseVoice 长音频质量衰减
VAD_CONFIG = sherpa_onnx.VadModelConfig()
VAD_CONFIG.silero_vad.model = MODEL_DIR + "/silero_vad.onnx"
VAD_CONFIG.silero_vad.min_silence_duration = 0.6
VAD_CONFIG.silero_vad.max_speech_duration = 30
VAD_CONFIG.sample_rate = 16000


def speech_chunks(pcm: np.ndarray, rate: int) -> list:
    """按静音切出语音段（单段不超 30 秒）；强噪声下 VAD 颗粒无收时整段兜底。"""
    vad = sherpa_onnx.VoiceActivityDetector(VAD_CONFIG, buffer_size_in_seconds=MAX_SECONDS + 20)
    window = VAD_CONFIG.silero_vad.window_size
    chunks = []

    def drain():
        while not vad.empty():
            segment = vad.front
            samples = np.asarray(segment.samples, dtype=np.float32)  # 必须先读样本再 pop
            vad.pop()
            if len(samples) > 0:
                chunks.append(samples)

    for start in range(0, len(pcm), window):
        piece = pcm[start:start + window]
        if len(piece) < window:
            piece = np.pad(piece, (0, window - len(piece)))
        vad.accept_waveform(piece)
        drain()
    vad.flush()
    drain()
    if sum(len(chunk) for chunk in chunks) < 0.1 * len(pcm):
        return [pcm]
    return chunks


def probe_duration(path: str) -> float:
    proc = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=nw=1:nk=1", path],
        capture_output=True, text=True)
    if proc.returncode != 0:
        raise BadAudio()
    return float(proc.stdout.strip())


def transcribe(audio: bytes) -> str:
    raw_fd, raw_path = tempfile.mkstemp(suffix=".audio")
    wav_fd, wav_path = tempfile.mkstemp(suffix=".wav")
    try:
        os.write(raw_fd, audio)
        os.close(raw_fd)
        raw_fd = None
        os.close(wav_fd)
        if probe_duration(raw_path) > MAX_SECONDS:
            raise TooLong()
        proc = subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", raw_path,
             "-ar", "16000", "-ac", "1", "-f", "wav", wav_path],
            capture_output=True)
        if proc.returncode != 0:
            raise BadAudio()
        with wave.open(wav_path, "rb") as w:
            frames = w.readframes(w.getnframes())
            rate = w.getframerate()
        pcm = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
        # 短音频单口直解；长音频 VAD 按静音分段，规避 SenseVoice 长音频质量衰减
        chunks = [pcm] if len(pcm) <= MAX_SINGLE_SHOT_SECONDS * rate else speech_chunks(pcm, rate)
        texts = []
        for chunk in chunks:
            with DECODE_LOCK:
                stream = RECOGNIZER.create_stream()
                stream.accept_waveform(rate, chunk)
                RECOGNIZER.decode_stream(stream)
            texts.append(TAG_RE.sub("", stream.result.text).strip())
        return "".join(texts)
    finally:
        if raw_fd is not None:
            os.close(raw_fd)
        for path in (raw_path, wav_path):
            if os.path.exists(path):
                os.unlink(path)


class Handler(BaseHTTPRequestHandler):

    def _send(self, status: int, content_type: str, payload: bytes):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, "application/json",
                       json.dumps({"status": "ok", "model": "sense-voice"}).encode())
        else:
            self._send(404, "text/plain", b"not found")

    def do_POST(self):
        if self.path != "/transcribe":
            self._send(404, "text/plain", b"not found")
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_BYTES:
            self._send(400, "text/plain", b"empty or oversized audio")
            return
        audio = self.rfile.read(length)
        try:
            text = transcribe(audio)
            self._send(200, "application/json",
                       json.dumps({"text": text}, ensure_ascii=False).encode())
        except (BadAudio, TooLong) as e:
            self._send(400, "text/plain", ("invalid audio: %s" % e).encode())
        except Exception as e:  # 推理故障：Java 侧将收到 502 并回落浏览器识别
            self._send(502, "text/plain", ("asr failed: %s" % e).encode())

    def log_message(self, fmt, *args):
        print("[asr]", fmt % args, flush=True)


if __name__ == "__main__":
    print("[asr] listening on", PORT, "model:", MODEL_DIR, flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
