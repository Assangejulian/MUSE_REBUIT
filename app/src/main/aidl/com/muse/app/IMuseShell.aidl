package com.muse.app;

interface IMuseShell {
    void destroy() = 16777114;
    String exec(String command) = 1;
    byte[] screenshot() = 2;
}
