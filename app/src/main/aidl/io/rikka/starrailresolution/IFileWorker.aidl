package io.rikka.starrailresolution;

/** Closed command interface: the caller cannot supply shell commands or file paths. */
interface IFileWorker {
    void destroy() = 16777114;
    String execute(String action, int longEdge, String gamePackage) = 1;
}
