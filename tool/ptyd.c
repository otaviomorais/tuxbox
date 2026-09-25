/*
 * ptyd — minimal PTY bridge daemon for the TuxBox Android app.
 *
 * Creates a Unix socket, waits for the app to connect, then spawns
 * the given command inside a new PTY and transparently bridges:
 *
 *   app socket <-> PTY master
 *
 * Protocol (binary, length-prefixed):
 *   Client -> daemon:
 *     [0x01][u32 len][bytes]   stdin data to write to PTY master
 *     [0x02][u32 len=8][r:u32 c:u32]  resize (TIOCSWINSZ)
 *   Daemon -> client:
 *     [0x01][u32 len][bytes]   output read from PTY master
 *     [0x03][u32 len=4][exit code]
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 LongQT-sea
 */
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pty.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define SOCK_PATH_MAX 108
#define BUF_SIZE 65536

static int g_child = -1;
static int g_child_status = 0;
static volatile sig_atomic_t g_child_reaped = 0;

/* v3.9e: log de diagnóstico num arquivo inspecionável pelo dev (o app roda o
 * ptyd por cima de um pty e descarta o stderr), para o caso "terminal abre
 * vazio e morre": queremos saber se o filho (tuxbox) executou, com que errno
 * falhou, e com que status saiu (127 = exec falhou, 137 = SIGKILL/LMK, etc.). */
static FILE *g_log = NULL;

static void xlog(const char *fmt, ...) {
    FILE *f = g_log;
    if (!f) return;
    va_list ap;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fputc('\n', f);
    fflush(f);
}

/*
 * Bionic AArch64 requires the PT_TLS segment to be aligned to 64 bytes.
 * If no TLS symbol in the link is aligned >= 64, lld emits align=8 and the
 * loader refuses to run the binary. Force it with a real dummy TLS object.
 */
__thread char __ptyd_tls_align_pad[64] __attribute__((aligned(64)));

static void die(const char *msg) {
    fprintf(stderr, "ptyd: %s: %s\n", msg, strerror(errno));
    _exit(1);
}

static void on_sigchld(int sig) {
    (void)sig;
    /* reap our child and record its status so the final exit code is preserved */
    while (g_child > 0) {
        int st = 0;
        pid_t r = waitpid(g_child, &st, WNOHANG);
        if (r == 0) break;      /* still running */
        if (r < 0) break;       /* already reaped / error */
        g_child_status = st;
        g_child_reaped = 1;
        break;
    }
}

static int make_listener(const char *path) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) die("socket");

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);

    unlink(path);
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) die("bind");
    if (listen(fd, 4) < 0) die("listen");
    return fd;
}

/* read exactly n bytes (best effort). Returns:
 *   n      complete read
 *   >0     partial read (fatal for frames)
 *   0      no data available right now (EAGAIN, keep waiting)
 *   -1     error
 *   -2     EOF (peer closed the stream)
 */
static ssize_t read_full(int fd, void *buf, size_t n) {
    size_t got = 0;
    while (got < n) {
        ssize_t r = read(fd, (char *)buf + got, n - got);
        if (r == 0) return -2; /* EOF: peer closed */
        if (r < 0) {
            if (errno == EINTR) continue;
            if ((errno == EAGAIN || errno == EWOULDBLOCK) && got == 0) return 0;
            return -1;
        }
        got += (size_t)r;
    }
    return (ssize_t)got;
}

static int write_all(int fd, const void *buf, size_t n) {
    size_t sent = 0;
    while (sent < n) {
        ssize_t r = write(fd, (const char *)buf + sent, n - sent);
        if (r <= 0) {
            if (r < 0 && errno == EINTR) continue;
            return -1;
        }
        sent += (size_t)r;
    }
    return 0;
}

/* send a message frame to the app */
static int msg(int fd, unsigned char type, const void *payload, uint32_t len) {
    unsigned char hdr[5];
    hdr[0] = type;
    uint32_t be = htonl(len);
    memcpy(hdr + 1, &be, 4);
    if (write_all(fd, hdr, 5) < 0) return -1;
    if (len > 0 && write_all(fd, payload, len) < 0) return -1;
    return 0;
}

int main(int argc, char **argv) {
    /* v3.11 — o log fica FORA de /data/local/tuxbox: essa pasta é a base dos
     * containers e o app lista tudo que está nela, então ptyd.log aparecia
     * como "container" e quebrava a lista do app. */
    mkdir("/data/local/tuxbox-logs", 0755);
    g_log = fopen("/data/local/tuxbox-logs/ptyd.log", "a");
    int use_stdio = 0;
    const char *sock_path = NULL;
    char **cmd;

    {
        int i;
        FILE *f = g_log;
        if (f) {
            fprintf(f, "=== ptyd pid=%d argc=%d ===", getpid(), argc);
            for (i = 0; i < argc; i++) fprintf(f, " %s", argv[i]);
            fputc('\n', f);
            fflush(f);
        }
    }

    if (argc >= 3 && strcmp(argv[1], "--stdio") == 0) {
        /* stdin/stdout transport: ptyd is exec'd by the app over su,
           frames go over fd 0 (in) and fd 1 (out). */
        use_stdio = 1;
        cmd = &argv[2];
    } else if (argc >= 4) {
        sock_path = argv[1];
        cmd = &argv[2];
    } else {
        fprintf(stderr, "usage: ptyd [--stdio|<socket-path>] <cmd> [args...]\n");
        return 2;
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_sigchld;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGCHLD, &sa, NULL);

    /* If the app closes its pipe (terminal closed), writes must fail with EPIPE
       instead of killing us outright, so we can kill the child and exit cleanly. */
    signal(SIGPIPE, SIG_IGN);

    int lfd = -1;
    int cfd = -1;
    if (use_stdio) {
        cfd = 1; /* use stdout fd for outgoing frames; incoming read from fd 0 */
    } else {
        lfd = make_listener(sock_path);
        cfd = accept(lfd, NULL, NULL);
        if (cfd < 0) die("accept");
        close(lfd);
    }

    /* create PTY. openpty() with a NULL winsize leaves the pty at 0 rows x
       0 cols, and full-screen TUIs (htop, opencode, vim with statuslines...)
       render NOTHING on a 0x0 tty until the app's resize frame arrives.
       Default to 80x24 so the shell always has a sane size; the first
       TIOCSWINSZ frame from the app overrides it with the real terminal size. */
    struct winsize ws0;
    ws0.ws_row = 24;
    ws0.ws_col = 80;
    ws0.ws_xpixel = 0;
    ws0.ws_ypixel = 0;
    int master, slave;
    if (openpty(&master, &slave, NULL, NULL, &ws0) < 0) die("openpty");

    /* ensure the dummy TLS object is not optimized out */
    __ptyd_tls_align_pad[0] = (char)(g_child & 1);

    g_child = fork();
    if (g_child < 0) die("fork");

    if (g_child == 0) {
        /* child: attach pty slave to stdio and exec command */
        close(master);
        setsid();
        if (ioctl(slave, TIOCSCTTY, 0) < 0) { /* ignore */ }
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        execvp(cmd[0], cmd);
        xlog("EXEC-FAIL pid=%d cmd=%s errno=%d (%s)", getpid(), cmd[0], errno, strerror(errno));
        _exit(127);
    }

    close(slave);

    int in_fd = use_stdio ? 0 : cfd;

    char inbuf[BUF_SIZE];
    char outbuf[BUF_SIZE];
    int run = 1;
    int cfd_flags = fcntl(cfd, F_GETFL, 0);
    int in_flags = fcntl(in_fd, F_GETFL, 0);
    int master_flags = fcntl(master, F_GETFL, 0);
    fcntl(cfd, F_SETFL, cfd_flags | O_NONBLOCK);
    fcntl(in_fd, F_SETFL, in_flags | O_NONBLOCK);
    fcntl(master, F_SETFL, master_flags | O_NONBLOCK);

    while (run) {
        /* input -> pty master */
        for (;;) {
            unsigned char hdr[5];
            ssize_t hr = read_full(in_fd, hdr, 5);
            if (hr < 0) { run = 0; break; } /* error or EOF (app closed the terminal) */
            if (hr == 0) {
                /* need more data */
                break;
            }
            unsigned char type = hdr[0];
            if (type == 0x01) {
                uint32_t len = ntohl(*(uint32_t *)(hdr + 1));
                if (len > sizeof(inbuf)) { run = 0; break; }
                ssize_t rr = read_full(in_fd, inbuf, len);
                if (rr < 0) { run = 0; break; }
                if (rr == 0) { run = 0; break; }
                write(master, inbuf, rr);
            } else if (type == 0x02) {
                uint32_t len = ntohl(*(uint32_t *)(hdr + 1));
                if (len != 8) { run = 0; break; }
                ssize_t rr = read_full(in_fd, inbuf, 8);
                if (rr != 8) { run = 0; break; }
                uint32_t rows = ntohl(*(uint32_t *)inbuf);
                uint32_t cols = ntohl(*(uint32_t *)(inbuf + 4));
                struct winsize ws;
                ws.ws_row = (unsigned short)rows;
                ws.ws_col = (unsigned short)cols;
                ws.ws_xpixel = 0;
                ws.ws_ypixel = 0;
                ioctl(master, TIOCSWINSZ, &ws);
            } else {
                run = 0; break;
            }
        }
        if (!run) break;

        /* pty master -> output */
        for (;;) {
            ssize_t n = read(master, outbuf, sizeof(outbuf));
            if (n < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) break;
                if (errno == EIO) { /* child exited */ }
                run = 0;
                break;
            }
            if (n == 0) { run = 0; break; }
            if (msg(cfd, 0x01, outbuf, (uint32_t)n) < 0) { run = 0; break; }
        }
        if (!run) break;

        usleep(10000);
    }

    /* kill child if still running (client disconnected or exited) */
    int status = 0;
    if (g_child > 0) {
        int st = 0;
        pid_t r = waitpid(g_child, &st, WNOHANG);
        if (r == 0) {
            kill(g_child, SIGKILL);
            usleep(50000);
            waitpid(g_child, &st, 0);
        }
        if (g_child_reaped) st = g_child_status;
        if (WIFEXITED(st)) {
            status = WEXITSTATUS(st);
            xlog("CHILD pid=%d cmd=%s EXITED status=%d", g_child, cmd[0], status);
        } else if (WIFSIGNALED(st)) {
            status = 128 + WTERMSIG(st);
            xlog("CHILD pid=%d cmd=%s SIGNALED sig=%d (exit=%d)", g_child, cmd[0], WTERMSIG(st), status);
        } else {
            status = 0;
            xlog("CHILD pid=%d cmd=%s UNKNOWN status=%d", g_child, cmd[0], st);
        }
    }

    /* notify exit code (big-endian, like the frame length) */
    uint32_t code = htonl((uint32_t)status);
    msg(cfd, 0x03, &code, 4);

    close(cfd);
    close(master);
    if (!use_stdio && sock_path != NULL) unlink(sock_path);
    return status;
}