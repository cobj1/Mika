#include <csignal>
#include <sys/prctl.h>
#include <unistd.h>

int llama_server(int argc, char ** argv);

int main(int argc, char ** argv) {
    // App 被系统结束时同时结束推理进程，避免留下占用内存的孤儿。
    const pid_t parent = getppid();
    if (parent == 1 || prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) {
        return 1;
    }
    return llama_server(argc, argv);
}
