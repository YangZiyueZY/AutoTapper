package com.example.autotapper;

interface ITapService {

    /** Shizuku 服务端保留事务号，用于结束本服务进程；必须存在。 */
    void destroy() = 16777114;

    /** 在 (x, y) 按下并保持 durationMs 后抬起；返回是否成功。 */
    boolean tap(int x, int y, int durationMs) = 1;
}
