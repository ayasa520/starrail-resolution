package io.rikka.starrailresolution;

/** Runs the same file operations as su, with the UID supplied by Shizuku. */
public final class ShizukuFileService extends IFileWorker.Stub {
    public ShizukuFileService() { }

    @Override public synchronized String execute(String action,int longEdge,String gamePackage) {
        // No shell strings, caller-controlled paths, or game code cross this
        // interface. Each request creates its own worker/diagnostics, because
        // unlike an app_process invocation this Binder process may be reused.
        String[] args="apply".equals(action)
            ?new String[]{"--package",gamePackage,action,Integer.toString(longEdge)}
            :new String[]{"--package",gamePackage,action};
        return RootMain.execute(args).toString();
    }

    @Override public synchronized void destroy() {
        // Shizuku reserves this transaction for service teardown. Synchronize
        // it with execute so teardown never interrupts a running transaction.
        System.exit(0);
    }
}
