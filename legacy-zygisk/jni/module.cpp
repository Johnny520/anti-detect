#include <jni.h>
#include <string.h>
#include <strings.h>
#include <stdlib.h>
#include <stdarg.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <stdio.h>
#include <stddef.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <link.h>
#include <spawn.h>
#include <android/log.h>

#include "zygisk.hpp"

using zygisk::Api;
using zygisk::AppSpecializeArgs;

#define LOG_TAG "AntiDetect"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/* ===================== 隐藏规则配置 ===================== */
// 路径里出现这些特征 → 伪装成“文件/目录不存在”
static const char *HIDDEN_PATHS[] = {
    "/sbin/su",
    "/system/bin/su",
    "/system/xbin/su",
    "/su/bin/su",
    "/data/local/su",
    "/data/local/bin/su",
    "/data/local/xbin/su",
    "/system/xbin/daemonsu",
    "/system/bin/.ext",
    "/system/app/superuser",
    "busybox",
    "/data/adb/magisk",
    "/sbin/.magisk",
    ".magisk",
    "/data/adb/modules",
    "/data/adb/modules_update",
    "libxposed",
    "/data/adb/lspd",
    "/data/adb/lsposed",
    "kernelsu",
    "/data/adb/ksu",
    "/data/adb/.ksu",
    "supersu",
    "daemonsu",
    "magisk",
    "xposed",
    "lposed",
    nullptr
};

// 需要伪装的属性：让“已 root / 已解锁 / debug 版”看起来是出厂状态
struct SpoofProp { const char *name; const char *value; };
static const SpoofProp SPOOF_PROPS[] = {
    {"ro.debuggable",            "0"},
    {"ro.secure",                "1"},
    {"ro.build.type",            "user"},
    {"ro.build.tags",            "release-keys"},
    {"ro.boot.flash.locked",     "1"},
    {"ro.boot.verifiedbootstate","green"},
    {"ro.boot.veritymode",       "enforcing"},
    {"ro.boot.warranty_bit",     "0"},
    {"ro.warranty_bit",          "0"},
    {"ro.vendor.boot.warranty_bit", "0"},
    {"ro.boot.fused_vbmeta_init_done", "1"},
    {"ro.kernel.qemu",           "0"},
    {nullptr, nullptr}
};

// 命令里出现这些关键字 → 伪装成“命令不存在”(system 返回 127)
static const char *HIDDEN_CMDS[] = {
    "su", "magisk", "kernelsu", "busybox", "daemonsu", "supersu", nullptr
};

/* ===================== 运行时状态 ===================== */
static Api *g_api = nullptr;
static JNIEnv *g_env = nullptr;
static int g_active = 0;            // 当前进程是否启用隐藏
static int g_enabled = 1;           // 模块总开关（默认开）
static char g_pkg[256] = {0};       // 当前进程包名
static char g_white[256][128];      // 白名单：这些 App 不隐藏
static int g_white_n = 0;

/* ===================== 工具函数 ===================== */
// 把 s 转小写后，判断是否包含 list 中任意子串
static int contains_any_lower(const char *s, const char *const *list) {
    if (!s) return 0;
    char buf[4096];
    size_t n = strlen(s);
    if (n >= sizeof(buf)) n = sizeof(buf) - 1;
    for (size_t i = 0; i < n; i++) {
        char c = s[i];
        buf[i] = (c >= 'A' && c <= 'Z') ? (char)(c + 32) : c;
    }
    buf[n] = '\0';
    for (int i = 0; list[i]; i++) {
        if (strstr(buf, list[i])) return 1;
    }
    return 0;
}

static int path_hidden(const char *path) { return contains_any_lower(path, HIDDEN_PATHS); }
static int cmd_hidden(const char *cmd)   { return contains_any_lower(cmd, HIDDEN_CMDS); }

/* ===================== 配置读取（提交 hook 之前，用真实 libc） ===================== */
static void load_config() {
    // 总开关：/data/adb/anti_detect/enabled 首字符为 '0' 则关闭
    FILE *f = fopen("/data/adb/anti_detect/enabled", "r");
    if (f) {
        char line[16];
        if (fgets(line, sizeof(line), f)) {
            if (line[0] == '0') g_enabled = 0;
        }
        fclose(f);
    }
    // 白名单：一行一个包名，# 开头为注释
    f = fopen("/data/adb/anti_detect/white.list", "r");
    if (f) {
        char line[256];
        while (fgets(line, sizeof(line), f) && g_white_n < 256) {
            size_t L = strlen(line);
            while (L > 0 && (line[L-1]=='\n' || line[L-1]=='\r' || line[L-1]==' ' || line[L-1]=='\t'))
                line[--L] = 0;
            if (L > 0 && line[0] != '#') {
                strncpy(g_white[g_white_n], line, 127);
                g_white[g_white_n][127] = 0;
                g_white_n++;
            }
        }
        fclose(f);
    }
}

static int pkg_whitelisted(const char *pkg) {
    if (!pkg) return 0;
    if (strcmp(pkg, "com.antidetect.app") == 0) return 1; // 自身设置 App 不隐藏
    for (int i = 0; i < g_white_n; i++) {
        if (strcmp(pkg, g_white[i]) == 0) return 1;
    }
    return 0;
}

/* ===================== 原函数指针 ===================== */
typedef int            (*orig_open_t)        (const char*, int, ...);
typedef int            (*orig_open64_t)      (const char*, int, ...);
typedef int            (*orig_openat_t)      (int, const char*, int, ...);
typedef FILE*          (*orig_fopen_t)       (const char*, const char*);
typedef FILE*          (*orig_freopen_t)     (const char*, const char*, FILE*);
typedef DIR*           (*orig_opendir_t)     (const char*);
typedef int            (*orig_access_t)      (const char*, int);
typedef int            (*orig_faccessat_t)   (int, const char*, int, int);
typedef int            (*orig_stat_t)        (const char*, struct stat*);
typedef int            (*orig_lstat_t)       (const char*, struct stat*);
typedef int            (*orig_stat64_t)      (const char*, struct stat64*);
typedef int            (*orig_lstat64_t)     (const char*, struct stat64*);
typedef int            (*orig_fstatat_t)     (int, const char*, struct stat*, int);
typedef int            (*orig_fstatat64_t)   (int, const char*, struct stat64*, int);
typedef int            (*orig_execve_t)      (const char*, char* const[], char* const[]);
typedef int            (*orig_system_t)      (const char*);
typedef int            (*orig_sysprop_get_t) (const char*, char*);
typedef int            (*orig_posix_spawn_t) (pid_t*, const char*, const void*, const void*, char* const[], char* const[]);

static orig_open_t          orig_open          = nullptr;
static orig_open64_t        orig_open64        = nullptr;
static orig_openat_t        orig_openat        = nullptr;
static orig_fopen_t         orig_fopen         = nullptr;
static orig_freopen_t       orig_freopen       = nullptr;
static orig_opendir_t       orig_opendir       = nullptr;
static orig_access_t        orig_access        = nullptr;
static orig_faccessat_t     orig_faccessat     = nullptr;
static orig_stat_t          orig_stat          = nullptr;
static orig_lstat_t         orig_lstat         = nullptr;
static orig_stat64_t        orig_stat64        = nullptr;
static orig_lstat64_t       orig_lstat64       = nullptr;
static orig_fstatat_t       orig_fstatat       = nullptr;
static orig_fstatat64_t     orig_fstatat64     = nullptr;
static orig_execve_t        orig_execve        = nullptr;
static orig_system_t        orig_system        = nullptr;
static orig_sysprop_get_t   orig_sysprop_get   = nullptr;
static orig_posix_spawn_t   orig_posix_spawn   = nullptr;
static orig_posix_spawn_t   orig_posix_spawnp  = nullptr;

/* ===================== 替换函数 ===================== */
static int my_open(const char *path, int flags, ...) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return -1; }
    va_list ap; va_start(ap, flags); int mode = va_arg(ap, int); va_end(ap);
    return orig_open ? orig_open(path, flags, mode) : -1;
}
static int my_open64(const char *path, int flags, ...) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return -1; }
    va_list ap; va_start(ap, flags); int mode = va_arg(ap, int); va_end(ap);
    return orig_open64 ? orig_open64(path, flags, mode) : -1;
}
static int my_openat(int dirfd, const char *path, int flags, ...) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return -1; }
    va_list ap; va_start(ap, flags); int mode = va_arg(ap, int); va_end(ap);
    return orig_openat ? orig_openat(dirfd, path, flags, mode) : -1;
}
static FILE* my_fopen(const char *path, const char *mode) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return nullptr; }
    return orig_fopen ? orig_fopen(path, mode) : nullptr;
}
static FILE* my_freopen(const char *path, const char *mode, FILE *stream) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return nullptr; }
    return orig_freopen ? orig_freopen(path, mode, stream) : nullptr;
}
static DIR* my_opendir(const char *name) {
    if (g_active && path_hidden(name)) { errno = ENOENT; return nullptr; }
    return orig_opendir ? orig_opendir(name) : nullptr;
}
static int my_access(const char *path, int mode) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return -1; }
    return orig_access ? orig_access(path, mode) : -1;
}
static int my_faccessat(int dirfd, const char *path, int mode, int flags) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return -1; }
    return orig_faccessat ? orig_faccessat(dirfd, path, mode, flags) : -1;
}
static int my_stat(const char *path, struct stat *buf) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return -1; }
    return orig_stat ? orig_stat(path, buf) : -1;
}
static int my_lstat(const char *path, struct stat *buf) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return -1; }
    return orig_lstat ? orig_lstat(path, buf) : -1;
}
static int my_stat64(const char *path, struct stat64 *buf) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return -1; }
    return orig_stat64 ? orig_stat64(path, buf) : -1;
}
static int my_lstat64(const char *path, struct stat64 *buf) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return -1; }
    return orig_lstat64 ? orig_lstat64(path, buf) : -1;
}
static int my_fstatat(int dirfd, const char *path, struct stat *buf, int flags) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return -1; }
    return orig_fstatat ? orig_fstatat(dirfd, path, buf, flags) : -1;
}
static int my_fstatat64(int dirfd, const char *path, struct stat64 *buf, int flags) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return -1; }
    return orig_fstatat64 ? orig_fstatat64(dirfd, path, buf, flags) : -1;
}
static int my_execve(const char *path, char *const argv[], char *const envp[]) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return -1; }
    return orig_execve ? orig_execve(path, argv, envp) : -1;
}
static int my_system(const char *command) {
    if (g_active && command && cmd_hidden(command)) return 127; // 伪装成命令不存在
    return orig_system ? orig_system(command) : 0;
}
static int my_posix_spawn(pid_t *pid, const char *path, const void *fa, const void *sa,
                          char *const argv[], char *const envp[]) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return -1; }
    return orig_posix_spawn ? orig_posix_spawn(pid, path, fa, sa, argv, envp) : -1;
}
static int my_posix_spawnp(pid_t *pid, const char *path, const void *fa, const void *sa,
                           char *const argv[], char *const envp[]) {
    if (g_active && path_hidden(path)) { errno = ENOENT; return -1; }
    return orig_posix_spawnp ? orig_posix_spawnp(pid, path, fa, sa, argv, envp) : -1;
}
static int my_sysprop_get(const char *name, char *value) {
    if (g_active && name) {
        for (int i = 0; SPOOF_PROPS[i].name; i++) {
            if (strcmp(name, SPOOF_PROPS[i].name) == 0) {
                strncpy(value, SPOOF_PROPS[i].value, PROP_VALUE_MAX - 1);
                value[PROP_VALUE_MAX - 1] = '\0';
                return (int)strlen(SPOOF_PROPS[i].value);
            }
        }
    }
    return orig_sysprop_get ? orig_sysprop_get(name, value) : 0;
}

/* ===================== 注册 PLT hook ===================== */
struct SymEntry { const char *sym; void *repl; void **orig; };
static const SymEntry SYMS[] = {
    {"open",                 (void*)my_open,           (void**)&orig_open},
    {"open64",               (void*)my_open64,         (void**)&orig_open64},
    {"openat",               (void*)my_openat,         (void**)&orig_openat},
    {"fopen",                (void*)my_fopen,          (void**)&orig_fopen},
    {"freopen",              (void*)my_freopen,        (void**)&orig_freopen},
    {"opendir",              (void*)my_opendir,        (void**)&orig_opendir},
    {"access",               (void*)my_access,         (void**)&orig_access},
    {"faccessat",            (void*)my_faccessat,      (void**)&orig_faccessat},
    {"stat",                 (void*)my_stat,           (void**)&orig_stat},
    {"lstat",                (void*)my_lstat,          (void**)&orig_lstat},
    {"__stat64",             (void*)my_stat64,         (void**)&orig_stat64},
    {"__lstat64",            (void*)my_lstat64,        (void**)&orig_lstat64},
    {"fstatat",              (void*)my_fstatat,        (void**)&orig_fstatat},
    {"__fstatat64",          (void*)my_fstatat64,      (void**)&orig_fstatat64},
    {"execve",               (void*)my_execve,         (void**)&orig_execve},
    {"system",               (void*)my_system,         (void**)&orig_system},
    {"posix_spawn",          (void*)my_posix_spawn,    (void**)&orig_posix_spawn},
    {"posix_spawnp",         (void*)my_posix_spawnp,   (void**)&orig_posix_spawnp},
    {"__system_property_get",(void*)my_sysprop_get,    (void**)&orig_sysprop_get},
    {nullptr, nullptr, nullptr}
};

// (dev, inode) 去重，避免同一库重复注册
#define MAX_LIBS 2048
static unsigned long long g_seen_dev[MAX_LIBS];
static unsigned long long g_seen_ino[MAX_LIBS];
static int g_seen_n = 0;

static int already_seen(dev_t dev, ino_t ino) {
    for (int i = 0; i < g_seen_n; i++) {
        if (g_seen_dev[i] == (unsigned long long)dev && g_seen_ino[i] == (unsigned long long)ino)
            return 1;
    }
    if (g_seen_n < MAX_LIBS) {
        g_seen_dev[g_seen_n] = (unsigned long long)dev;
        g_seen_ino[g_seen_n] = (unsigned long long)ino;
        g_seen_n++;
    }
    return 0;
}

static int phdr_callback(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size; (void)data;
    if (!info->dlpi_name || info->dlpi_name[0] == '\0') return 0;
    struct stat st;
    if (stat(info->dlpi_name, &st) != 0) return 0;   // 此刻 hook 未提交，用的是真实 stat
    if (already_seen(st.st_dev, st.st_ino)) return 0;
    for (int i = 0; SYMS[i].sym; i++) {
        g_api->pltHookRegister(st.st_dev, st.st_ino, SYMS[i].sym, SYMS[i].repl, SYMS[i].orig);
    }
    return 0;
}

/* ===================== 模块主体 ===================== */
class AntiDetectModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        g_api = api;
        g_env = env;
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        if (!g_api || !g_env) return;

        // 取进程名（即包名，可能带 :service 之类后缀）
        const char *proc = g_env->GetStringUTFChars(args->nice_name, nullptr);
        if (proc) {
            strncpy(g_pkg, proc, 255); g_pkg[255] = '\0';
            g_env->ReleaseStringUTFChars(args->nice_name, proc);
            char *colon = (char*)strchr(g_pkg, ':');
            if (colon) *colon = '\0';
        }

        // 读取配置（此刻 hook 尚未提交，走真实 libc）
        load_config();

        if (!g_enabled || pkg_whitelisted(g_pkg)) {
            g_active = 0;
            return;
        }
        g_active = 1;

        // 枚举已加载库并注册 PLT hook，然后统一提交
        g_seen_n = 0;
        dl_iterate_phdr(phdr_callback, nullptr);
        g_api->pltHookCommit();

        LOGD("anti-detect active for %s", g_pkg);
    }

    void postAppSpecialize(const AppSpecializeArgs *args) override {
        (void)args;
    }
};

REGISTER_ZYGISK_MODULE(AntiDetectModule)
