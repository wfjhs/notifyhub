#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
本地假 webhook：模拟企业微信群机器人（或任意 HTTP 接收端）。

用途：没有企业微信、也不想花短信费时，用它验证「NotifyHub 真的把请求发出来了」。
它会把收到的 JSON 原样打印，并返回企业微信约定的成功报文
（{"errcode":0,"errmsg":"ok"}），这样消息状态能正常流转到 SUCCESS。

用法：
    python tools/fake_webhook.py                # 默认监听 9000
    python tools/fake_webhook.py --port 9100

然后在管理台「管理对象」里新增：
    名称：本地测试群
    通道：企业微信
    地址：http://localhost:9000/webhook

发送后，这个终端会打印收到的报文；消息状态应当变成 SUCCESS。
如果状态是 FAILED/DEAD 而这里什么也没打印，说明请求根本没发出来——
问题在通道实现或地址，不在这里。
"""
import argparse
import json
import sys
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Windows 控制台默认 GBK，打印中文会炸，这里强制切 UTF-8；
# line_buffering 是为了重定向到文件/管道时也能实时看到（否则要攒满缓冲区才输出）
try:
    sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
except Exception:
    pass


class Handler(BaseHTTPRequestHandler):
    received = 0

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""

        Handler.received += 1
        now = datetime.now().strftime("%H:%M:%S")

        print("=" * 60)
        print(f"[{now}] 第 {Handler.received} 条  路径={self.path}")
        try:
            body = json.loads(raw.decode("utf-8"))
            print(json.dumps(body, ensure_ascii=False, indent=2))
            # 顺手把消息正文提取出来，省得在一堆 JSON 里找
            content = (body.get("text") or {}).get("content")
            if content:
                print(f"--- 正文：{content}")
        except Exception:
            print("（非 JSON 报文）")
            print(raw.decode("utf-8", errors="replace"))

        # 企业微信的成功报文。真实通道会校验 errcode，
        # 不返回这个的话消息会一直重试到进死信。
        resp = json.dumps({"errcode": 0, "errmsg": "ok"}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def do_GET(self):
        # 方便浏览器直接打开确认服务活着
        resp = f"fake webhook 正在运行，已接收 {Handler.received} 条\n".encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, *args):
        # 关掉默认的访问日志，只留我们自己的格式化输出
        pass


def main():
    parser = argparse.ArgumentParser(description="模拟企业微信群机器人的本地 webhook")
    parser.add_argument("--port", type=int, default=9000, help="监听端口，默认 9000")
    args = parser.parse_args()

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print("=" * 60)
    print(f"fake webhook 已启动：http://localhost:{args.port}/webhook")
    print("在 NotifyHub 里新增发送对象：通道=企业微信，地址填上面这个 URL")
    print("Ctrl+C 退出")
    print("=" * 60)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print(f"\n共接收 {Handler.received} 条，已退出")


if __name__ == "__main__":
    main()
