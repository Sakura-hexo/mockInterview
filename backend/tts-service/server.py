"""TTS HTTP 边车：Java 后端经此调用本地 matcha 模型合成 wav。

独立容器与 asr 边车同构：推理依赖（sherpa-onnx）与模型全部烘在镜像内，
运行零外联、零密钥。换装自 edge-tts（微软免费朗读通道）：实测到微软链路
晚高峰吞吐仅约 300kbps，189 字要等 6 秒；本地 matcha RTF 0.03，同文本约
1 秒出声，且不再受网络波动影响（选型记录见 .agentdocs）。
HTTP 契约不变：POST /synthesize 同入参，输出从 mp3 改 wav；单说话人，
嗓音清单只剩 matcha-zh-en 一项。
"""

import io
import json
import threading
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
import sherpa_onnx

PORT = 8000
MAX_TEXT = 600
MODEL_DIR = "/opt/model/matcha-zh-en"
VOICES = ["matcha-zh-en"]


def build_tts() -> sherpa_onnx.OfflineTts:
    matcha = sherpa_onnx.OfflineTtsMatchaModelConfig()
    matcha.acoustic_model = MODEL_DIR + "/model-steps-3.onnx"
    matcha.vocoder = MODEL_DIR + "/vocos-16khz-univ.onnx"
    matcha.lexicon = MODEL_DIR + "/lexicon.txt"
    matcha.tokens = MODEL_DIR + "/tokens.txt"
    # 英文单词经 espeak 音素化读出（纯中文版会直接吞掉 Redis 这类术语）
    matcha.data_dir = MODEL_DIR + "/espeak-ng-data"
    model = sherpa_onnx.OfflineTtsModelConfig(matcha=matcha, num_threads=4)
    config = sherpa_onnx.OfflineTtsConfig(
        model=model,
        # 日期、数字、手机号按中文习惯正则化（fst 顺序即应用顺序）
        rule_fsts=",".join(MODEL_DIR + "/" + name
                           for name in ("date-zh.fst", "number-zh.fst", "phone-zh.fst")),
    )
    return sherpa_onnx.OfflineTts(config)


TTS = build_tts()
# onnxruntime 会话并发推理不安全；单次合成约 1 秒，串行无压力
GENERATE_LOCK = threading.Lock()


def synthesize_wav(text: str) -> bytes:
    with GENERATE_LOCK:
        audio = TTS.generate(text, sid=0, speed=1.0)
    samples = (np.asarray(audio.samples, dtype=np.float32) * 32768) \
        .clip(-32768, 32767).astype("<i2")
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(audio.sample_rate)
        w.writeframes(samples.tobytes())
    return buffer.getvalue()


class Handler(BaseHTTPRequestHandler):

    def _send(self, status: int, content_type: str, payload: bytes):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path == "/voices":
            self._send(200, "application/json", json.dumps({"voices": VOICES}).encode())
        elif self.path == "/health":
            self._send(200, "text/plain", b"ok")
        else:
            self._send(404, "text/plain", b"not found")

    def do_POST(self):
        if self.path != "/synthesize":
            self._send(404, "text/plain", b"not found")
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length))
            text = (body.get("text") or "")[:MAX_TEXT].strip()
            voice = body.get("voice") or VOICES[0]
        except Exception:
            self._send(400, "text/plain", b"bad request")
            return
        if not text or voice not in VOICES:
            self._send(400, "text/plain", b"invalid text or voice")
            return
        try:
            wav = synthesize_wav(text)
            self._send(200, "audio/wav", wav)
        except Exception as e:  # 模型故障：Java 侧将收到 502 并回落浏览器朗读
            self._send(502, "text/plain", ("tts failed: %s" % e).encode())

    def log_message(self, fmt, *args):
        print("[tts]", fmt % args, flush=True)


if __name__ == "__main__":
    print("[tts] listening on", PORT, "voices:", VOICES, flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
