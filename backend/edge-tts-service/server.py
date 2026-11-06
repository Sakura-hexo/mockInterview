"""edge-tts HTTP 边车：Java 后端经此调用微软朗读通道合成 mp3。

独立容器的原因：微软鉴权算法轮换频繁（v1 明文哈希已被 403，现行 v2 为魔改
SHA256 加 CS64 双摘要），官方 Python 库跟进最快；协议再变只需升级本镜像的
edge-tts 版本，Java 侧保持纯 HTTP 不动。
"""

import asyncio
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

import edge_tts

PORT = 8000
MAX_TEXT = 600
VOICES = ["zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural", "zh-CN-YunyangNeural"]


async def collect_mp3(text: str, voice: str) -> bytes:
    chunks = []
    communicate = edge_tts.Communicate(text, voice)
    async for chunk in communicate.stream():
        if chunk["type"] == "audio":
            chunks.append(chunk["data"])
    if not chunks:
        raise RuntimeError("no audio received")
    return b"".join(chunks)


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
            mp3 = asyncio.run(collect_mp3(text, voice))
            self._send(200, "audio/mpeg", mp3)
        except Exception as e:  # 微软通道故障：Java 侧将收到 502 并回落浏览器朗读
            self._send(502, "text/plain", ("edge-tts failed: %s" % e).encode())

    def log_message(self, fmt, *args):
        print("[edge-tts]", fmt % args, flush=True)


if __name__ == "__main__":
    print("[edge-tts] listening on", PORT, "voices:", VOICES, flush=True)
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
