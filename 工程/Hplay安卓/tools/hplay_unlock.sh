#!/system/bin/sh
# Hplay 隐私锁自动解锁（坐标 2026-10-06 用 getevent 实测标定）
input tap 540 1347   # 隐私锁页「解锁」
sleep 3
input tap 535 1876   # 0
sleep 1
input tap 806 1333   # 6
sleep 1
input tap 858 1046   # 3
sleep 1
input tap 580 995    # 2
sleep 3
echo done