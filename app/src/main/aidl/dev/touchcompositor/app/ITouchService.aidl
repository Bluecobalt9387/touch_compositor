package dev.touchcompositor.app;
interface ITouchService {
    void destroy() = 16777114;
    String start() = 1;
    String status() = 2;
    String identity() = 3;
}
