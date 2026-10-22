package io.rikka.starrailresolution;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Runs only this APK's own file worker in a separate root process. */
final class RootGateway {
    private final String apk;
    RootGateway(Context context) { apk=context.getApplicationInfo().sourceDir; }

    JSONObject execute(String action,Integer width) throws Exception {
        if(!java.util.Arrays.asList("status","apply","official","undo").contains(action)) throw new IOException("未知操作");
        String command="CLASSPATH="+quote(apk)+" /system/bin/app_process /system/bin "
            +RootMain.class.getName()+" "+action+(width==null?"":" "+width);
        Process process=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
        ExecutorService reader=Executors.newSingleThreadExecutor();
        Future<String> captured=reader.submit(()-> {
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
            try(InputStream input=process.getInputStream()) {
                while((count=input.read(buffer))!=-1) {
                    if(output.size()+count>1000000) throw new IOException("诊断输出超出预期");
                    output.write(buffer,0,count);
                }
            }
            return output.toString("UTF-8");
        });
        try {
            // A new su authorization can wait for user input. File writes run
            // off the UI thread; killing the Activity never interrupts a
            // partially written game resource because replacement is atomic.
            if(!process.waitFor(120,TimeUnit.SECONDS)) throw new IOException("Root 请求超时，请授予权限后重新检测");
            String output=captured.get(5,TimeUnit.SECONDS);
            for(String line:output.split("\\r?\\n")) if(line.startsWith(RootMain.RESULT_PREFIX)) {
                return new JSONObject(line.substring(RootMain.RESULT_PREFIX.length()));
            }
            throw new IOException("未能启动 Root 文件助手，请检查授权。"+(output.trim().isEmpty()?"":"\n"+output.substring(0,Math.min(1500,output.length()))));
        } finally { process.destroy();reader.shutdownNow(); }
    }
    private static String quote(String value) { return "'"+value.replace("'","'\\''")+"'"; }
}
