package com.shizukutrackpad.app;

interface IInputInjectorService {
    void destroy() = 16777114;

    void setTargetDisplay(int displayId, int width, int height) = 1;

    void moveCursor(float dx, float dy) = 2;

    void leftClick() = 3;

    void injectKeyEvent(int keyCode, int metaState, int displayId) = 4;

    float getCursorX() = 5;

    float getCursorY() = 6;

    void resetCursorToCenter() = 7;

    String getLastError() = 8;

    void injectPasteText(String text, int targetDisplayId) = 10;

    void rightClick() = 11;

    void scroll(float hScroll, float vScroll) = 12;

    void beginLeftDrag() = 13;

    void dragMove(float dx, float dy) = 14;

    void endLeftDrag() = 15;
}
