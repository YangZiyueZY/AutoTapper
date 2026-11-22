package com.example.autotapper;

interface ITapService {

    /** Shizuku 服务端保留事务号，用于结束本服务进程；必须存在。 */
    void destroy() = 16777114;

    /** 在 (x, y) 按下并保持 durationMs 后抬起；返回是否成功。 */
    boolean tap(int x, int y, int durationMs) = 1;

    /** 从 (x1,y1) 滑到 (x2,y2)，总时长 durationMs。 */
    boolean swipe(int x1, int y1, int x2, int y2, int durationMs) = 2;

    /** 多指同时按下并保持 holdMs；最多 10 指。 */
    boolean multiTouch(in int[] xs, in int[] ys, int holdMs) = 3;

    /** 注入一个按键（Android KeyEvent 键码，例如 3=HOME 4=BACK 82=MENU 187=APP_SWITCH）。 */
    boolean keyEvent(int keyCode) = 4;

    /** 往当前焦点输入框输入文本。 */
    boolean inputText(String text) = 5;
}
