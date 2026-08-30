#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <unistd.h>

static void die(const char *what) {
    fprintf(stderr, "%s: %s\n", what, strerror(errno));
    exit(2);
}

int main(int argc, char **argv) {
    if (argc < 2 || argc > 3) {
        fprintf(stderr, "usage: tc_evgrab /dev/input/eventX [timeout_sec]\n");
        return 2;
    }
    int timeout_sec = argc == 3 ? atoi(argv[2]) : 20;
    if (timeout_sec <= 0 || timeout_sec > 120) timeout_sec = 20;
    prctl(PR_SET_PDEATHSIG, SIGTERM);
    alarm((unsigned)timeout_sec);

    int fd = open(argv[1], O_RDONLY | O_CLOEXEC);
    if (fd < 0) die("open");
    if (ioctl(fd, EVIOCGRAB, 1) < 0) die("EVIOCGRAB");
    fprintf(stderr, "EVGRAB_READY %s timeout=%d\n", argv[1], timeout_sec);

    struct input_event ev[64];
    for (;;) {
        ssize_t n = read(fd, ev, sizeof(ev));
        if (n < 0) {
            if (errno == EINTR) continue;
            die("read");
        }
        if (n == 0) break;
        const unsigned char *p = (const unsigned char *)ev;
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(STDOUT_FILENO, p + off, (size_t)(n - off));
            if (w < 0) {
                if (errno == EINTR) continue;
                die("write");
            }
            off += w;
        }
    }
    ioctl(fd, EVIOCGRAB, 0);
    close(fd);
    return 0;
}
