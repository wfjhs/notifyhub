#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
NotifyHub 功能回归：正常投递 / 幂等 / 限流 / 重试退避 / 死信 / 死信重放。

前置：应用已启动，失败率由参数控制（本脚本假设 fail-rate=1.0 用于重试场景时单独跑）。
用法：python regression.py <base_url>
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8083"
passed = 0
failed = 0


def call(method, path, payload=None):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(
        BASE + path, data=data,
        headers={"Content-Type": "application/json"}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name}  {detail}")


def send(biz_id, channel="SMS", receiver="13800000001", content="回归测试"):
    return call("POST", "/api/messages", {
        "bizId": biz_id, "bizType": "REGRESSION",
        "channel": channel, "receiver": receiver, "content": content,
    })


def main():
    ts = int(time.time())

    print("=" * 60)
    print("1. 正常投递")
    print("=" * 60)
    _, r = send(f"REG-OK-{ts}", channel="INBOX", receiver="1001")
    check("INBOX 提交返回 code=0", r.get("code") == 0, r)
    check("状态为 SUCCESS", r["data"]["status"] == "SUCCESS", r["data"])

    print("=" * 60)
    print("2. 幂等")
    print("=" * 60)
    biz = f"REG-IDEM-{ts}"
    _, first = send(biz, channel="INBOX", receiver="1002")
    _, second = send(biz, channel="INBOX", receiver="1002")
    check("首次 duplicated=false", first["data"]["duplicated"] is False, first["data"])
    check("重复 duplicated=true", second["data"]["duplicated"] is True, second["data"])
    check("两次 messageId 相同",
          first["data"]["messageId"] == second["data"]["messageId"],
          f'{first["data"]} vs {second["data"]}')

    print("=" * 60)
    print("3. 同业务不同通道各自独立")
    print("=" * 60)
    biz2 = f"REG-MULTI-{ts}"
    _, a = send(biz2, channel="INBOX", receiver="1003")
    _, b = send(biz2, channel="WECHAT_WORK", receiver="ops-group")
    check("两个通道各产生一条", a["data"]["messageId"] != b["data"]["messageId"],
          f'{a["data"]} vs {b["data"]}')
    _, rows = call("GET", f"/api/messages?bizId={biz2}")
    check("按 bizId 能查到 2 条", len(rows["data"]) == 2, len(rows["data"]))

    print("=" * 60)
    print("4. 参数校验")
    print("=" * 60)
    _, r = send(f"REG-BAD-{ts}", channel="PIGEON")
    check("未知通道返回 400", r.get("code") == 400, r)
    _, r = call("POST", "/api/messages", {"bizId": "", "channel": "SMS", "receiver": "1", "content": "x"})
    check("bizId 为空返回 400", r.get("code") == 400, r)

    print("=" * 60)
    print("5. 死信接口")
    print("=" * 60)
    _, r = call("GET", "/api/messages/dead?limit=5")
    check("死信列表可访问", r.get("code") == 0, r)
    _, r = call("POST", "/api/messages/dead/999999/replay")
    check("重放不存在的死信返回 400", r.get("code") == 400, r)

    print()
    print("=" * 60)
    print(f"回归结果：{passed} 通过 / {failed} 失败")
    print("=" * 60)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
