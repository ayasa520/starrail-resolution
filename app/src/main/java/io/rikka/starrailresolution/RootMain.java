package io.rikka.starrailresolution;

import android.os.Process;
import android.system.Os;
import android.system.StructStat;
import io.rikka.starrailresolution.core.*;
import org.json.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** A separate short-lived file worker; it never loads code into the game. */
public final class RootMain {
    public static final String RESULT_PREFIX="STAR_RAIL_RESOLUTION_RESULT:";
    private static final String PACKAGE="com.HoYoverse.hkrpgoversea";
    private static final File RESOURCE_DIR=new File("/sdcard/Android/data/"+PACKAGE+"/files/DesignData/Android");
    private static final File BACKUPS=new File("/sdcard/Download/StarRailResolution");

    private static final class Session {
        File pack; ResourceManifest.Entry location; byte[] entry; ProfileConfig config;
        String currentMd5, gpu; int screenWidth,screenHeight;
    }

    public static void main(String[] args) {
        JSONObject response=new JSONObject();
        try {
            if(Process.myUid()!=0) throw new IOException("需要授予 Root 权限才能读取游戏资源");
            if(args.length<1) throw new IOException("缺少操作参数");
            String action=args[0];
            if(!Arrays.asList("status","apply","official","undo","selftest").contains(action)) throw new IOException("未知操作");
            if(action.equals("selftest")) {
                response.put("ok",true).put("message",selfTest());
            } else if(action.equals("status")) {
                Session session=inspect();response=status(session).put("ok",true);
            } else {
                // Serialize writers across UI restarts and multiple root
                // worker processes. This lock is outside the game directory.
                try(RandomAccessFile lockFile=new RandomAccessFile("/data/local/tmp/starrail-resolution.lock","rw");
                    FileLock lock=lockFile.getChannel().tryLock()) {
                    if(lock==null) throw new IOException("另一个修改正在进行，请稍后刷新");
                    Session session=inspect();
                    byte[] replacement;
                    if(action.equals("apply")) {
                        if(args.length!=2) throw new IOException("缺少目标分辨率");
                        replacement=session.config.changeHighest(ResolutionSpec.forLongEdge(Integer.parseInt(args[1]),session.screenWidth,session.screenHeight));
                    } else if(action.equals("official")) replacement=session.config.originalEntry();
                    else replacement=undoEntry(session);
                    if(Arrays.equals(session.entry,replacement)) {
                        response=status(session).put("ok",true).put("message","当前已是所选档位，无需重启");
                    } else {
                        stopGame();
                        ResolutionSpec old=session.config.resolution(4);
                        ResolutionSpec target=new ProfileConfig(replacement).resolution(4);
                        ResourceTransaction transaction=new ResourceTransaction(RootMain::commitOnAndroid);
                        ResourceTransaction.Journal journal=new ResourceTransaction.Journal() {
                            JSONObject record;
                            public void prepared(File dir,String before,String after) throws IOException {
                                try {
                                    record=new JSONObject().put("status","prepared").put("action",action)
                                        .put("package",PACKAGE).put("versionCode",118)
                                        .put("packName",session.pack.getName()).put("packSize",session.location.packSize)
                                        .put("entryOffset",session.location.offset).put("entryLength",session.location.size)
                                        .put("beforeMd5",before).put("afterMd5",after)
                                        .put("beforeEntrySha256",Hashing.sha256(session.entry))
                                        .put("afterEntrySha256",Hashing.sha256(replacement))
                                        .put("beforeRectangle",new JSONArray(new int[]{old.width,old.height}))
                                        .put("afterRectangle",new JSONArray(new int[]{target.width,target.height}))
                                        .put("screen",new JSONArray(new int[]{session.screenWidth,session.screenHeight}))
                                        .put("internal3dDirectlyMeasured",false);
                                    writeJson(new File(dir,"change.json"),record);
                                } catch(JSONException e) { throw new IOException(e); }
                            }
                            public void completed(File dir,String after) throws IOException {
                                try { record.put("status","completed");writeJson(new File(dir,"change.json"),record); }
                                catch(JSONException e) { throw new IOException(e); }
                            }
                        };
                        ResourceTransaction.Result changed=transaction.apply(session.pack,session.location,session.entry,
                            replacement,session.currentMd5,BACKUPS,journal);
                        session.entry=replacement;session.currentMd5=changed.afterMd5;session.config=new ProfileConfig(replacement);
                        boolean restarted=false;String launchError="";
                        try {
                            String launch=command("am","start","-W","--windowingMode","1","-n",PACKAGE+"/com.mihoyo.combosdk.ComboSDKActivity");
                            restarted=launch.contains("Status: ok");
                            if(!restarted) launchError="系统未确认启动完成，请手动全屏启动游戏";
                        } catch(IOException e) { launchError="配置已保存，请手动全屏启动游戏"; }
                        response=status(session).put("ok",true).put("restarted",restarted)
                            .put("backup",changed.backup.getName())
                            .put("message",restarted?"已校验写入并全屏重启游戏":launchError);
                    }
                }
            }
        } catch(Throwable error) {
            try { response=new JSONObject().put("ok",false).put("error",error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()); }
            catch(JSONException impossible) { throw new AssertionError(impossible); }
            error.printStackTrace(System.err);
        }
        System.out.println(RESULT_PREFIX+response);
    }

    private static Session inspect() throws Exception {
        String installed=command("cmd","package","list","packages","--show-versioncode",PACKAGE);
        if(!installed.trim().equals("package:"+PACKAGE+" versionCode:118")) {
            throw new IOException("首版仅支持已验证的国际服 4.6.0（118），当前版本未修改");
        }
        JSONObject vulkan=new JSONObject(command("cmd","gpu","vkjson"));
        String gpu=vulkan.getJSONArray("devices").getJSONObject(0).getJSONObject("properties").getString("deviceName");
        if(!gpu.matches(".*Adreno.*830.*")) throw new IOException("首版仅适配已验证的 Adreno 830，当前设备未修改");
        String size=command("wm","size");
        if(size.contains("Override")) throw new IOException("存在系统分辨率覆盖；助手不会更改系统显示设置，请先恢复后再检测");
        Matcher dimensions=Pattern.compile("Physical size: (\\d+)x(\\d+)").matcher(size);
        if(!dimensions.find()) throw new IOException("无法读取屏幕原生尺寸");
        int a=Integer.parseInt(dimensions.group(1)),b=Integer.parseInt(dimensions.group(2));
        int width=Math.max(a,b),height=Math.min(a,b);
        if(width!=3840 || height!=2560) throw new IOException("首版仅验证了 3840×2560 平板，当前设备未修改");
        File[] manifests=RESOURCE_DIR.listFiles((dir,name)->name.matches("DesignV_[0-9a-f]{32}\\.bytes"));
        if(manifests==null || manifests.length==0) throw new IOException("未找到资源清单，请先完成游戏下载");
        Session found=null;IOException last=null;int valid=0;
        for(File manifest:manifests) {
            try {
                ResourceManifest.Entry location=ResourceManifest.locate(readLimited(manifest,16000000));
                File pack=new File(RESOURCE_DIR,location.packMd5+".bytes");
                if(!pack.isFile() || pack.length()!=location.packSize) throw new IOException("资源包缺失或大小不符");
                byte[] entry=ResourceTransaction.readEntry(pack,location);
                ProfileConfig config=new ProfileConfig(entry);
                // Validate the entire pack against the unmodified manifest by
                // virtually restoring the resolution table for the checksum.
                // This accepts our earlier adb edits while refusing unrelated
                // changes, corruption, or a different game's resource format.
                if(!Hashing.substitutedMd5(pack,location.offset,config.originalEntry()).equals(location.packMd5)) {
                    throw new IOException("资源包存在分辨率配置以外的变化，已停止");
                }
                valid++;
                found=new Session();found.pack=pack;found.location=location;found.entry=entry;found.config=config;
                found.currentMd5=Hashing.md5(pack);found.gpu=gpu;found.screenWidth=width;found.screenHeight=height;
            } catch(IOException error) { last=error; }
        }
        if(found==null) throw last==null?new IOException("未找到可用的设备配置"):last;
        if(valid!=1) throw new IOException("存在多个有效资源清单，无法唯一定位当前配置");
        return found;
    }

    private static JSONObject status(Session session) throws Exception {
        ResolutionSpec resolution=session.config.resolution(4);
        File previous=lastCompletedBackup(BACKUPS,session.currentMd5);
        boolean undo=false;
        if(previous!=null) {
            JSONObject record=new JSONObject(new String(readLimited(new File(previous,"change.json"),32000),StandardCharsets.UTF_8));
            undo=record.optString("afterMd5").equals(session.currentMd5);
        }
        JSONArray table=new JSONArray();
        for(int i=0;i<5;i++) {
            ResolutionSpec spec=session.config.resolution(i);
            table.put(new JSONObject().put("width",spec.width).put("height",spec.height)
                .put("predicted",spec.predictedLabel(session.screenWidth,session.screenHeight)));
        }
        return new JSONObject().put("version","4.6.0").put("gpu",session.gpu)
            .put("screenWidth",session.screenWidth).put("screenHeight",session.screenHeight)
            .put("targetWidth",resolution.width).put("targetHeight",resolution.height)
            .put("predicted",resolution.predictedLabel(session.screenWidth,session.screenHeight))
            .put("table",table).put("canUndo",undo).put("backupDirectory",BACKUPS.getAbsolutePath())
            .put("grade",savedResolutionGrade()).put("internal3dDirectlyMeasured",false);
    }

    private static int savedResolutionGrade() {
        // Only return the graphics grade; never serialize account preferences
        // or SDK payloads into diagnostics or backups.
        try {
            File file=new File("/data/user/0/"+PACKAGE+"/shared_prefs/"+PACKAGE+".v2.playerprefs.xml");
            String xml=new String(readLimited(file,1000000),StandardCharsets.UTF_8);
            Matcher value=Pattern.compile("<string name=\"GraphicsSettings_Model\">([^<]+)</string>").matcher(xml);
            if(value.find()) return new JSONObject(URLDecoder.decode(value.group(1),"UTF-8")).getInt("ResolutionQuality");
        } catch(Exception ignored) { }
        return -1;
    }

    private static byte[] undoEntry(Session session) throws Exception {
        File previous=lastCompletedBackup(BACKUPS,session.currentMd5);
        if(previous==null) throw new IOException("尚无助手创建的回退备份");
        JSONObject record=new JSONObject(new String(readLimited(new File(previous,"change.json"),32000),StandardCharsets.UTF_8));
        if(!record.getString("afterMd5").equals(session.currentMd5)
                || !record.getString("packName").equals(session.pack.getName())
                || record.getLong("entryOffset")!=session.location.offset || record.getInt("entryLength")!=session.location.size) {
            throw new IOException("当前资源与上次备份不匹配，未恢复");
        }
        byte[] before=readLimited(new File(previous,"before-entry.bytes"),1000000);
        if(before.length!=session.location.size || !Hashing.sha256(before).equals(record.getString("beforeEntrySha256"))) {
            throw new IOException("回退备份校验失败，未恢复");
        }
        new ProfileConfig(before);
        return before;
    }

    private static File lastCompletedBackup(File backupRoot,String currentMd5) throws Exception {
        File[] directories=backupRoot.listFiles(file->file.isDirectory()&&file.getName().startsWith("revert "));
        if(directories==null) return null;
        Arrays.sort(directories,Comparator.comparing(File::getName).reversed());
        for(File dir:directories) {
            try {
                JSONObject json=new JSONObject(new String(readLimited(new File(dir,"change.json"),32000),StandardCharsets.UTF_8));
                // A worker can be killed after the atomic rename but before
                // updating its journal. Reconcile that narrow crash window
                // from the complete-file checksum instead of losing undo.
                if(json.optString("status").equals("prepared") && json.optString("afterMd5").equals(currentMd5)) {
                    json.put("status","completed");writeJson(new File(dir,"change.json"),json);
                }
                if(json.optString("status").equals("completed")) return dir;
            } catch(Exception ignored) { }
        }
        return null;
    }

    private static void stopGame() throws IOException {
        command("am","force-stop",PACKAGE);
        for(int attempt=0;attempt<20;attempt++) {
            java.lang.Process process=new ProcessBuilder("pidof",PACKAGE).start();
            try {
                int code=process.waitFor();
                if(code==1) return;
                if(code!=0) throw new IOException("无法确认游戏已经停止");
                Thread.sleep(100);
            }
            catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IOException("停止等待被中断",e); }
        }
        throw new IOException("游戏尚未退出，未写入配置");
    }

    private static void commitOnAndroid(File prepared,File destination) throws IOException {
        try {
            StructStat original=Os.stat(destination.getAbsolutePath()),temporary=Os.stat(prepared.getAbsolutePath());
            if(original.st_uid!=temporary.st_uid || original.st_gid!=temporary.st_gid) {
                Os.chown(prepared.getAbsolutePath(),original.st_uid,original.st_gid);
            }
            if((original.st_mode&0777)!=(temporary.st_mode&0777)) Os.chmod(prepared.getAbsolutePath(),original.st_mode&0777);
            Os.rename(prepared.getAbsolutePath(),destination.getAbsolutePath());
        } catch(Exception error) { throw new IOException("无法原子替换资源文件，原文件未被分段写入",error); }
    }

    private static byte[] readLimited(File file,int limit) throws IOException {
        if(file.length()>limit) throw new IOException("文件大小超出预期");
        byte[] bytes=Files.readAllBytes(file.toPath());
        if(bytes.length>limit) throw new IOException("文件大小超出预期");
        return bytes;
    }
    private static void writeJson(File file,JSONObject json) throws IOException,JSONException {
        // Preserve the prepared journal if the completed-journal write runs
        // out of space or is interrupted. The next status read can reconcile
        // that intact prepared record against the committed pack checksum.
        File temporary=File.createTempFile(".journal-",".tmp",file.getParentFile());
        try {
            ResourceTransaction.writeSynced(temporary,json.toString(2).getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temporary.toPath());}
    }
    private static String command(String... args) throws IOException {
        java.lang.Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        try(InputStream stream=process.getInputStream()) {
            while((count=stream.read(buffer))!=-1) {
                if(out.size()+count>4000000) throw new IOException("系统命令输出超出预期");
                out.write(buffer,0,count);
            }
            int code=process.waitFor();
            if(code!=0) throw new IOException("系统命令失败："+args[0]+"（"+code+"）");
            return out.toString("UTF-8");
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IOException(e); }
        finally { process.destroy(); }
    }

    private static String selfTest() throws Exception {
        Session actual=inspect();
        File scratch=new File(BACKUPS,"selftest-"+UUID.randomUUID());
        if(!scratch.mkdirs()) throw new IOException("无法创建自检目录");
        try {
            byte[] before=actual.entry,after=actual.config.changeHighest(new ResolutionSpec(2880,1440));
            if(Arrays.equals(before,after)) after=actual.config.changeHighest(new ResolutionSpec(3414,1707));
            File fake=new File(scratch,"fixture.bytes");
            byte[] fakeBytes=new byte[before.length+64];Arrays.fill(fakeBytes,(byte)0x55);
            System.arraycopy(before,0,fakeBytes,32,before.length);ResourceTransaction.writeSynced(fake,fakeBytes);
            String originalMd5=Hashing.md5(fake);
            ResourceManifest.Entry location=new ResourceManifest.Entry(originalMd5,fake.length(),32,before.length);
            ResourceTransaction.Journal journal=new ResourceTransaction.Journal() {
                public void prepared(File dir,String b,String a) throws IOException {
                    try {writeJson(new File(dir,"change.json"),new JSONObject().put("status","prepared").put("beforeMd5",b).put("afterMd5",a));}
                    catch(JSONException e) {throw new IOException(e);}
                }
                // Deliberately retain the prepared journal to simulate loss
                // of the final status update after a successful atomic rename.
                public void completed(File dir,String a) { }
            };
            ResourceTransaction transaction=new ResourceTransaction(RootMain::commitOnAndroid);
            ResourceTransaction.Result applied=transaction.apply(fake,location,before,after,originalMd5,new File(scratch,"backups"),journal);
            if(!Arrays.equals(ResourceTransaction.readEntry(fake,location),after)) throw new IOException("模拟写入自检失败");
            File reconciled=lastCompletedBackup(new File(scratch,"backups"),applied.afterMd5);
            if(reconciled==null || !reconciled.equals(applied.backup)) throw new IOException("中断后的回退记录恢复自检失败");
            transaction.apply(fake,location,after,before,applied.afterMd5,new File(scratch,"backups"),journal);
            if(!Hashing.md5(fake).equals(originalMd5)) throw new IOException("模拟恢复自检失败");
            return "真实游戏资源只读检测通过；独立副本在外部存储的备份、原子写入、权限保留和恢复均通过。未修改或停止游戏。";
        } finally { deleteScratch(scratch); }
    }
    private static void deleteScratch(File file) throws IOException {
        if(file.isDirectory()) { File[] children=file.listFiles();if(children!=null) for(File child:children) deleteScratch(child); }
        Files.deleteIfExists(file.toPath());
    }
}
