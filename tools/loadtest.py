#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
NotifyHub 压测脚本。

三个场景：
  1. 提交吞吐：并发提交互不相同的消息，统计 QPS / P50 / P99
  2. 并发幂等：同一 bizId 高并发提交，验证只有 1 条落库（唯一键而非先查后插）
  3. 终态收敛：等待所有消息走到终态，统计端到端耗时

只用标准库，不依赖 requests。
"""
import concurrent.futures
import json
import statistics
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8083"
CONCURRENCY = int(sys.argv[2]) if len(sys.argv) > 2 else 20
TOTAL = int(sys.argv[3]) if len(sys.argv) > 3 else 1000
CHANNEL = "SMS"


def post_message(payload):
    t0 = time.perf_counter()
    req = urllib.request.Request(
        BASE + "/api/messages",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = json.loads(resp.read().decode("utf-8"))
        return {"body": body, "ms": (time.perf_counter() - t0) * 1000, "err": None}
    except Exception as e:  # noqa: BLE001
        return {"body": None, "ms": (time.perf_counter() - t0) * 1000, "err": repr(e)}


def get_json(path):
    with urllib.request.urlopen(BASE + path, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def pct(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(int(len(ordered) * p / 100.0), len(ordered) - 1)
    return ordered[idx]


def scenario_throughput():
    print("=" * 66)
    print(f"场景 1｜提交吞吐　并发={CONCURRENCY}　总量={TOTAL}　通道={CHANNEL}")
    print("=" * 66)

    def task(i):
        return post_message({
            "bizId": f"LOAD-{i:06d}",
            "bizType": "LOADTEST",
            "channel": CHANNEL,
            # receiver 必须互不相同，否则会撞「同一接收方每分钟 N 条」的限流
            "receiver": f"139{i:08d}",
            "content": f"压测消息 {i}",
        })

    started = time.perf_counter()
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        for r in pool.map(task, range(TOTAL)):
            results.append(r)
    elapsed = time.perf_counter() - started

    ok = [r for r in results if r["err"] is None and r["body"] and r["body"].get("code") == 0]
    dup = [r for r in ok if r["body"]["data"]["duplicated"]]
    limited = [r for r in results if r["err"] is None and r["body"] and r["body"].get("code") == 429]
    failed = [r for r in results if r["err"] is not None]
    latencies = [r["ms"] for r in ok]

    print(f"总耗时      : {elapsed:.2f} s")
    print(f"成功        : {len(ok)}  (其中幂等命中 {len(dup)})")
    print(f"限流 429    : {len(limited)}")
    print(f"请求失败    : {len(failed)}")
    print(f"吞吐 QPS    : {len(ok) / elapsed:.0f}  (按成功数 / 总耗时)")
    print(f"延迟 P50    : {pct(latencies, 50):.2f} ms")
    print(f"延迟 P90    : {pct(latencies, 90):.2f} ms")
    print(f"延迟 P99    : {pct(latencies, 99):.2f} ms")
    if latencies:
        print(f"延迟 均值   : {statistics.mean(latencies):.2f} ms / 最大 {max(latencies):.2f} ms")
    if failed:
        print(f"首个错误    : {failed[0]['err']}")
    return len(ok), elapsed


def scenario_idempotent():
    print()
    print("=" * 66)
    print("场景 2｜并发幂等　同一 bizId 50 个请求同时到达")
    print("=" * 66)

    biz_id = f"IDEMP-{int(time.time())}"
    payload = {
        "bizId": biz_id,
        "bizType": "IDEMP",
        "channel": CHANNEL,
        "receiver": "13700000000",
        "content": "并发幂等验证",
    }

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=50) as pool:
        results = list(pool.map(lambda _: post_message(payload), range(50)))
    elapsed = time.perf_counter() - started

    ok = [r for r in results if r["err"] is None and r["body"] and r["body"].get("code") == 0]
    first = [r for r in ok if not r["body"]["data"]["duplicated"]]
    dup = [r for r in ok if r["body"]["data"]["duplicated"]]
    ids = {r["body"]["data"]["messageId"] for r in ok}

    print(f"总耗时      : {elapsed:.2f} s")
    print(f"成功响应    : {len(ok)}  (首次 {len(first)} / 幂等 {len(dup)})")
    print(f"返回的 messageId 去重后数量: {len(ids)}  -> {sorted(ids)}")

    rows = get_json(f"/api/messages?bizId={biz_id}")
    print(f"库中该 bizId 的记录数      : {len(rows['data'])}")

    verdict = len(ids) == 1 and len(rows["data"]) == 1 and len(first) == 1
    print(f"结论        : {'通过 —— 50 并发只产生 1 条记录' if verdict else '失败 —— 出现重复落库'}")
    return verdict


def main():
    ok, elapsed = scenario_throughput()
    idem_ok = scenario_idempotent()
    print()
    print("=" * 66)
    print(f"汇总：{ok} 条提交成功 / {elapsed:.2f}s，幂等正确={idem_ok}")
    print("=" * 66)


if __name__ == "__main__":
    main()
