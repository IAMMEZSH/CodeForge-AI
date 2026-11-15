#include "sha256.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#else
#include <sys/statvfs.h>
#include <sys/utsname.h>
#include <unistd.h>
#if defined(__APPLE__)
#include <sys/sysctl.h>
#include <sys/types.h>
#else
#include <sys/sysinfo.h>
#endif
#endif

#ifdef _WIN32

static char *arg_utf8(int index, wchar_t **wargv) {
    int need;
    char *out;
    if (index < 0 || wargv[index] == NULL) {
        return NULL;
    }
    need = WideCharToMultiByte(CP_UTF8, 0, wargv[index], -1, NULL, 0, NULL, NULL);
    if (need <= 0) {
        return NULL;
    }
    out = (char *)malloc((size_t)need);
    if (out == NULL) {
        return NULL;
    }
    if (WideCharToMultiByte(CP_UTF8, 0, wargv[index], -1, out, need, NULL, NULL) <= 0) {
        free(out);
        return NULL;
    }
    return out;
}

static wchar_t *arg_wide(int index, wchar_t **wargv) {
    return (index >= 0 && wargv[index] != NULL) ? wargv[index] : NULL;
}

#endif  // _WIN32

static int print_hash(const char *path) {
    char hex[65];
    if (path == NULL || cf_sha256_file(path, hex) != 0) {
        printf("{\"ok\":false,\"error\":\"cannot_read_file\"}\n");
        return 2;
    }
    printf("{\"ok\":true,\"sha256\":\"%s\"}\n", hex);
    return 0;
}

static int verify_hash(const char *path, const char *expected) {
    char hex[65];
    int match;
    size_t i;

    if (path == NULL || expected == NULL || cf_sha256_file(path, hex) != 0) {
        printf("{\"ok\":false,\"error\":\"cannot_read_file\"}\n");
        return 2;
    }
    match = (strlen(expected) == 64);
    for (i = 0; match && i < 64; ++i) {
        char a = expected[i];
        char b = hex[i];
        if (a >= 'A' && a <= 'F') {
            a = (char)(a - 'A' + 'a');
        }
        if (a != b) {
            match = 0;
        }
    }
    printf("{\"ok\":true,\"match\":%s,\"sha256\":\"%s\"}\n", match ? "true" : "false", hex);
    return match ? 0 : 3;
}

#ifdef _WIN32

static const char *arch_name(WORD arch) {
    switch (arch) {
        case PROCESSOR_ARCHITECTURE_AMD64:
            return "x64";
        case PROCESSOR_ARCHITECTURE_ARM64:
            return "arm64";
        case PROCESSOR_ARCHITECTURE_INTEL:
            return "x86";
        default:
            return "unknown";
    }
}

static int print_sysinfo(const wchar_t *probe_path) {
    SYSTEM_INFO si;
    MEMORYSTATUSEX mem;
    ULARGE_INTEGER free_bytes, total_bytes, avail_bytes;
    wchar_t cwd[MAX_PATH];
    const wchar_t *path = probe_path != NULL ? probe_path : cwd;
    unsigned long long total_mb;
    unsigned long long avail_mb;
    unsigned long long free_mb;
    unsigned long long disk_total_mb;

    GetNativeSystemInfo(&si);
    mem.dwLength = sizeof(mem);
    GlobalMemoryStatusEx(&mem);
    if (probe_path == NULL) {
        GetCurrentDirectoryW(MAX_PATH, cwd);
    }
    if (!GetDiskFreeSpaceExW(path, &avail_bytes, &total_bytes, &free_bytes)) {
        printf("{\"ok\":false,\"error\":\"cannot_query_disk\"}\n");
        return 2;
    }

    total_mb = (unsigned long long)(mem.ullTotalPhys / (1024ull * 1024ull));
    avail_mb = (unsigned long long)(mem.ullAvailPhys / (1024ull * 1024ull));
    free_mb = (unsigned long long)(free_bytes.QuadPart / (1024ull * 1024ull));
    disk_total_mb = (unsigned long long)(total_bytes.QuadPart / (1024ull * 1024ull));

    printf("{\"ok\":true,\"cpu_logical\":%lu,\"cpu_arch\":\"%s\","
           "\"page_size\":%lu,\"mem_total_mb\":%llu,\"mem_avail_mb\":%llu,"
           "\"disk_free_mb\":%llu,\"disk_total_mb\":%llu}\n",
           (unsigned long)si.dwNumberOfProcessors, arch_name(si.wProcessorArchitecture),
           (unsigned long)si.dwPageSize, total_mb, avail_mb, free_mb, disk_total_mb);
    return 0;
}

#else  // POSIX

static const char *arch_name(void) {
    static struct utsname info;
    if (uname(&info) != 0) {
        return "unknown";
    }
    if (strcmp(info.machine, "x86_64") == 0 || strcmp(info.machine, "amd64") == 0) {
        return "x64";
    }
    if (strcmp(info.machine, "aarch64") == 0 || strcmp(info.machine, "arm64") == 0) {
        return "arm64";
    }
    if (strcmp(info.machine, "i686") == 0 || strcmp(info.machine, "i386") == 0) {
        return "x86";
    }
    if (strncmp(info.machine, "armv7", 5) == 0) {
        return "arm";
    }
    return info.machine;
}

static int memory_mb(unsigned long long *total, unsigned long long *avail) {
#if defined(__APPLE__)
    unsigned long long memsize = 0;
    size_t length = sizeof(memsize);
    if (sysctlbyname("hw.memsize", &memsize, &length, NULL, 0) != 0) {
        return -1;
    }
    *total = memsize / (1024ull * 1024ull);
    *avail = 0;
    return 0;
#else
    struct sysinfo info;
    if (sysinfo(&info) != 0) {
        return -1;
    }
    *total = (unsigned long long)info.totalram * (unsigned long long)info.mem_unit /
             (1024ull * 1024ull);
    *avail = (unsigned long long)info.freeram * (unsigned long long)info.mem_unit /
             (1024ull * 1024ull);
    return 0;
#endif
}

static int print_sysinfo(const char *probe_path) {
    unsigned long long mem_total_mb = 0;
    unsigned long long mem_avail_mb = 0;
    unsigned long long disk_total_mb = 0;
    unsigned long long disk_free_mb = 0;
    struct statvfs vfs;
    const char *path = probe_path != NULL ? probe_path : ".";
    long cores = sysconf(_SC_NPROCESSORS_ONLN);
    long page = sysconf(_SC_PAGESIZE);

    if (cores <= 0) {
        cores = 1;
    }
    if (page <= 0) {
        page = 4096;
    }
    if (memory_mb(&mem_total_mb, &mem_avail_mb) != 0) {
        printf("{\"ok\":false,\"error\":\"cannot_query_memory\"}\n");
        return 2;
    }
    if (statvfs(path, &vfs) != 0) {
        printf("{\"ok\":false,\"error\":\"cannot_query_disk\"}\n");
        return 2;
    }
    disk_total_mb = (unsigned long long)vfs.f_blocks *
                    (unsigned long long)vfs.f_frsize / (1024ull * 1024ull);
    disk_free_mb = (unsigned long long)vfs.f_bavail *
                   (unsigned long long)vfs.f_frsize / (1024ull * 1024ull);

    printf("{\"ok\":true,\"cpu_logical\":%ld,\"cpu_arch\":\"%s\","
           "\"page_size\":%ld,\"mem_total_mb\":%llu,\"mem_avail_mb\":%llu,"
           "\"disk_free_mb\":%llu,\"disk_total_mb\":%llu}\n",
           cores, arch_name(), page, mem_total_mb, mem_avail_mb,
           disk_free_mb, disk_total_mb);
    return 0;
}

#endif  // _WIN32

#ifdef _WIN32
int main(void) {
    int wargc = 0;
    wchar_t **wargv = CommandLineToArgvW(GetCommandLineW(), &wargc);
    const char *cmd;
    int code = 2;

    if (wargv == NULL) {
        printf("{\"ok\":false,\"error\":\"command_line_error\"}\n");
        return 2;
    }

    SetConsoleOutputCP(CP_UTF8);
    if (wargc < 2) {
        printf("{\"ok\":false,\"error\":\"usage: cforge_native "
               "hash|verify|sysinfo|version\"}\n");
        LocalFree(wargv);
        return 2;
    }

    cmd = arg_utf8(1, wargv);
    if (cmd == NULL) {
        printf("{\"ok\":false,\"error\":\"utf8_conversion_failed\"}\n");
        LocalFree(wargv);
        return 2;
    }

    if (strcmp(cmd, "hash") == 0 && wargc >= 3) {
        char *path = arg_utf8(2, wargv);
        code = print_hash(path);
        free(path);
    } else if (strcmp(cmd, "verify") == 0 && wargc >= 4) {
        char *path = arg_utf8(2, wargv);
        char *expected = arg_utf8(3, wargv);
        code = verify_hash(path, expected);
        free(path);
        free(expected);
    } else if (strcmp(cmd, "sysinfo") == 0) {
        wchar_t *path = wargc >= 3 ? arg_wide(2, wargv) : NULL;
        code = print_sysinfo(path);
    } else if (strcmp(cmd, "version") == 0) {
        printf("{\"ok\":true,\"component\":\"native-c\",\"version\":\"%s\"}\n",
               cf_sha256_version());
        code = 0;
    } else {
        printf("{\"ok\":false,\"error\":\"invalid_arguments\"}\n");
        code = 2;
    }

    free((void *)cmd);
    LocalFree(wargv);
    return code;
}

#else  // POSIX

int main(int argc, char **argv) {
    if (argc < 2) {
        printf("{\"ok\":false,\"error\":\"usage: cforge_native "
               "hash|verify|sysinfo|version\"}\n");
        return 2;
    }
    if (strcmp(argv[1], "hash") == 0 && argc >= 3) {
        return print_hash(argv[2]);
    }
    if (strcmp(argv[1], "verify") == 0 && argc >= 4) {
        return verify_hash(argv[2], argv[3]);
    }
    if (strcmp(argv[1], "sysinfo") == 0) {
        return print_sysinfo(argc >= 3 ? argv[2] : NULL);
    }
    if (strcmp(argv[1], "version") == 0) {
        printf("{\"ok\":true,\"component\":\"native-c\",\"version\":\"%s\"}\n",
               cf_sha256_version());
        return 0;
    }
    printf("{\"ok\":false,\"error\":\"invalid_arguments\"}\n");
    return 2;
}

#endif  // _WIN32
