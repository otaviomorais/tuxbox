/* test client for ptyd - connects to unix socket, sends/receives frames */
#include <arpa/inet.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/select.h>

static int write_all(int fd, const void *b, size_t n) {
    size_t got = 0;
    while (got < n) {
        ssize_t r = write(fd, (const char*)b + got, n - got);
        if (r <= 0) return -1;
        got += r;
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: tclient <sock>\n"); return 2; }
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un a; memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX; strncpy(a.sun_path, argv[1], sizeof a.sun_path - 1);
    if (connect(fd, (struct sockaddr*)&a, sizeof a) < 0) { perror("connect"); return 1; }

    /* send a resize first: [0x02][len=8][rows:4][cols:4] */
    char frame[13];
    frame[0] = 0x02;
    uint32_t be_len = htonl(8);
    memcpy(frame + 1, &be_len, 4);
    uint32_t rows = htonl(24), cols = htonl(80);
    memcpy(frame + 5, &rows, 4);
    memcpy(frame + 9, &cols, 4);
    write_all(fd, frame, 13);

    fd_set rset;
    int running = 1;
    while (running) {
        FD_ZERO(&rset);
        FD_SET(0, &rset);
        FD_SET(fd, &rset);
        select(fd+1, &rset, NULL, NULL, NULL);
        if (FD_ISSET(0, &rset)) {
            char buf[4096]; ssize_t n = read(0, buf, sizeof buf);
            if (n <= 0) running = 0;
            else {
                char hdr[5]; hdr[0] = 0x01;
                uint32_t l = htonl((uint32_t)n);
                memcpy(hdr+1, &l, 4);
                write_all(fd, hdr, 5);
                write_all(fd, buf, n);
            }
        }
        if (FD_ISSET(fd, &rset)) {
            char hdr[5];
            ssize_t h = read(fd, hdr, 5);
            if (h <= 0) { running = 0; break; }
            uint32_t l = ntohl(*(uint32_t*)(hdr+1));
            char *buf = malloc(l ? l : 1);
            size_t got = 0;
            while (got < l) { ssize_t x = read(fd, buf+got, l-got); if (x <= 0) break; got += x; }
            if (hdr[0] == 0x01) write(1, buf, got);
            else if (hdr[0] == 0x03) { printf("\n[exit=%u]\n", *(uint32_t*)buf); running = 0; }
            free(buf);
        }
    }
    return 0;
}