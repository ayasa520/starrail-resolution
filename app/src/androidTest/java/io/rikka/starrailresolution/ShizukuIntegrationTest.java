package io.rikka.starrailresolution;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import org.json.*;
import rikka.shizuku.Shizuku;
import io.rikka.starrailresolution.core.GameTarget;
import java.util.concurrent.*;

/** Device checks use the real authorized Binder and only disposable test files. */
public final class ShizukuIntegrationTest extends Instrumentation {
    private String gamePackage=GameTarget.INTERNATIONAL.packageName;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if(arguments!=null) gamePackage=arguments.getString("gamePackage",gamePackage);
        start();
    }

    @Override public void onStart() {
        Bundle result=new Bundle();
        CountDownLatch received=new CountDownLatch(1);
        Shizuku.OnBinderReceivedListener listener=received::countDown;
        try {
            Shizuku.addBinderReceivedListenerSticky(listener);
            // The manager delivers its binder when the target app becomes
            // active. Start the real Activity rather than treating an idle
            // instrumentation-only process as an application launch.
            startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            require(received.await(15,TimeUnit.SECONDS),"No Shizuku binder received");
            JSONObject unprivileged=RootMain.execute(new String[]{"status"});
            require(!unprivileged.getBoolean("ok"),"The app UID must not execute privileged file operations");
            // The selected package travels through the real AIDL boundary,
            // not just a direct RootMain invocation. Run this check for each
            // installed country variant to catch lost/defaulted selectors.
            GameTarget game=GameTarget.forPackage(gamePackage);
            ShizukuGateway gateway=new ShizukuGateway(getTargetContext(),game);
            JSONObject before=gateway.execute("status",null);require(before.getBoolean("ok"),before.toString());
            require(game.packageName.equals(before.getString("package")),"Status returned the wrong game installation");
            int uid=before.getJSONObject("diagnostics").getInt("workerUid");
            require(uid==Shizuku.getUid(),"Worker UID must match the granted Shizuku identity");
            JSONObject checked=gateway.execute("selftest",null);require(checked.getBoolean("ok"),checked.toString());
            JSONObject after=gateway.execute("status",null);require(after.getBoolean("ok"),after.toString());
            String oldHash=before.getJSONObject("diagnostics").getJSONArray("resourceCandidates")
                .getJSONObject(0).getString("currentPackMd5");
            String newHash=after.getJSONObject("diagnostics").getJSONArray("resourceCandidates")
                .getJSONObject(0).getString("currentPackMd5");
            require(oldHash.equals(newHash),"Live game pack changed during isolated selftest");
            result.putString("stream","Shizuku Binder status, isolated backup/edit/restore, UID isolation, and unchanged live pack: PASS\n");
            result.putInt("workerUid",uid);result.putString("gamePackMd5",newHash);
            result.putString("gameVersion",after.getString("version"));
            result.putString("gamePackage",game.packageName);
            finish(-1,result);
        } catch(Exception | AssertionError error) {
            android.util.Log.e("ShizukuIntegrationTest","Integration check failed",error);
            result.putString("stream",error.toString()+"\n");finish(0,result);
        } finally {Shizuku.removeBinderReceivedListener(listener);}
    }

    private static void require(boolean condition,String message) {
        if(!condition) throw new AssertionError(message);
    }
}
