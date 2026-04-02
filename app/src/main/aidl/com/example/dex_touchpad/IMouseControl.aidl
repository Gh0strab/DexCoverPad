package com.example.dex_touchpad;

interface IMouseControl {
    void moveCursor(float deltaX, float deltaY);
    void sendClick(int buttonCode);
    void sendScroll(float verticalDelta, float horizontalDelta);
    void destroy();
}
