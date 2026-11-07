#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
测量补偿扫描吞吐：插入 N 条 PENDING 消息，统计多久全部变成 SUCCESS。

用法：
  python measure_scan.py <前缀> [总数]

补偿链路的吞吐由两个参数决定：
  batch-size   每批捞多少
  scan-interval-ms  上一批处理完后等多久再下一批
理论吞吐 ≈ batch-size / (单批处理耗时 + 间隔)
"""
import subprocess
import sys
import time

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
PREFIX = sys.argv[1] if len(sys.argv) > 1 else "SCAN-X"
TOTAL = int(sys.argv[2]) if len(sys.argv) > 2 else 500


def sql(q):
    r = subprocess.run([MYSQL, "-uroot", "-p123456", "-N", "-e", "USE notifyhub; " + q],
                       capture_output=True, text=True, encoding="utf-8")
    return r.stdout.strip()


def insert_pending(n):
    sql(f"""
    INSERT INTO message (biz_id, biz_type, channel, receiver, content, status,
                         retry_count, max_retry, create_time, update_time, deleted)
    WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n+1 FROM seq WHERE n < {n})
    SELECT CONCAT('{PREFIX}-', LPAD(n,4,'0')), 'SCAN', 'SMS',
           CONCAT('136', LPAD(n,8,'0')), CONCAT('积压消息 ', n),
           'PENDING', 0, 3, NOW(), NOW(), 0 FROM seq
    """)


def main():
    print("=" * 66)
    print(f"补偿扫描吞吐　前缀={PREFIX}　批次={TOTAL}")
    print("=" * 66)

    sql(f"DELETE FROM message WHERE biz_id LIKE '{PREFIX}-%'")
    insert_pending(TOTAL)
    pending = int(sql(f"SELECT COUNT(*) FROM message WHERE biz_id LIKE '{PREFIX}-%' AND status='PENDING'"))
    print(f"已插入待补偿消息: {pending} 条")

    started = time.perf_counter()
    last_report = 0
    while True:
        done = int(sql(f"SELECT COUNT(*) FROM message WHERE biz_id LIKE '{PREFIX}-%' AND status='SUCCESS'"))
        elapsed = time.perf_counter() - started
        if done >= pending:
            print(f"全部完成        : {pending} 条 / {elapsed:.1f}s")
            print(f"扫描吞吐        : {pending / elapsed:.1f} 条/秒")
            break
        if elapsed - last_report >= 5:
            print(f"  t={elapsed:5.1f}s  已完成 {done:4d}/{pending}  ({done / elapsed:.1f} 条/秒)")
            last_report = elapsed
        if elapsed > 180:
            print(f"超时：仅完成 {done}/{pending}")
            break
        time.sleep(1)

    print("\n最终状态分布：")
    print(sql(f"SELECT status, COUNT(*) FROM message WHERE biz_id LIKE '{PREFIX}-%' GROUP BY status"))


if __name__ == "__main__":
    main()
