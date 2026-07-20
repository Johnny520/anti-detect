#!/system/bin/sh
# 模块安装时执行（Magisk 在刷入时调用）
# 创建配置目录与默认白名单（空 = 对所有用户 App 生效）

mkdir -p /data/adb/anti_detect
if [ ! -f /data/adb/anti_detect/white.list ]; then
    # 默认空：对所有 App 隐藏。需要"放行"某 App 时，往这个文件写一行包名
    touch /data/adb/anti_detect/white.list
fi

# 模块总开关，1=开 0=关
if [ ! -f /data/adb/anti_detect/enabled ]; then
    echo "1" > /data/adb/anti_detect/enabled
fi

exit 0
