#!/system/bin/sh
# 早期阶段执行（文件系统挂载完成后）
# 确保配置目录存在（防止用户在模块外手动删了）

mkdir -p /data/adb/anti_detect
[ -f /data/adb/anti_detect/white.list ] || touch /data/adb/anti_detect/white.list
[ -f /data/adb/anti_detect/enabled ] || echo "1" > /data/adb/anti_detect/enabled

exit 0
