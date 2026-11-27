package io.rikka.starrailresolution;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import io.rikka.starrailresolution.core.GameTarget;

/** Binds a narrowly scoped Shizuku UserService; it never falls back to su. */
final class ShizukuGateway implements PrivilegedGateway {
    private final Context context;
    private final GameTarget game;

    ShizukuGateway(Context context) {this(context,GameTarget.INTERNATIONAL);}
    ShizukuGateway(Context context,GameTarget game) {this.context=context.getApplicationContext();this.game=game;}

    @Override public JSONObject execute(String action,Integer width) throws Exception {
        if(!java.util.Arrays.asList("status","apply","official","undo","selftest").contains(action)) throw new IOException("未知操作");
        if(!Shizuku.pingBinder()) throw new IOException("Shizuku 未运行，请先启动 Shizuku 后重新检测");
        if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) throw new IOException("尚未授予 Shizuku 权限");
        CompletableFuture<IFileWorker> connected=new CompletableFuture<>();
        ServiceConnection connection=new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name,IBinder binder) {
                if(binder!=null && binder.pingBinder()) connected.complete(IFileWorker.Stub.asInterface(binder));
                else connected.completeExceptionally(new IOException("Shizuku 文件助手 Binder 不可用"));
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                connected.completeExceptionally(new IOException("Shizuku 文件助手已断开，请重新检测"));
            }
        };
        // Give every request a distinct service identity. A retired Activity
        // can then release its own worker without killing a replacement
        // Activity's in-flight write. Teardown happens only after execute has
        // returned, so a UI recreation does not cancel a resource transaction.
        Shizuku.UserServiceArgs args=new Shizuku.UserServiceArgs(new ComponentName(context,ShizukuFileService.class))
            .tag("resolution-"+UUID.randomUUID()).daemon(false)
            .version((int)context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode())
            .processNameSuffix("file_worker");
        try {
            Shizuku.bindUserService(args,connection);
            IFileWorker service=connected.get(20,TimeUnit.SECONDS);
            JSONObject result=new JSONObject(service.execute(action,width==null?0:width,game.packageName));
            result.put("authorization","Shizuku");return result;
        } catch(TimeoutException error) {
            throw new IOException("Shizuku 文件助手连接超时，请检查 Shizuku 运行状态",error);
        } catch(RemoteException error) {
            // An IPC failure after a write must be surfaced, never retried or
            // switched to another privilege backend: doing so could apply an
            // undo twice. A fresh status request reconciles the existing journal.
            throw new IOException("Shizuku 连接中断，请重新检测以确认配置状态",error);
        } finally {
            try {Shizuku.unbindUserService(args,connection,true);}
            catch(RuntimeException error) {android.util.Log.w("ShizukuGateway","Cannot release worker",error);}
        }
    }
}
