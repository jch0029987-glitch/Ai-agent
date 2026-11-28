package com.jeremy.ai.agent;

interface IShizukuShellService {
    String execShell(String command, long timeoutMs);
    void destroy();
}

