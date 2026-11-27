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
    private GameTarget game=GameTarget.INTERNATIONAL;
    private final JSONObject diagnostics=new JSONObject();
    private boolean writerLockHeld;

    private static final class Session {
        File pack; ResourceManifest.Entry location; byte[] entry; ProfileConfig config;
        VerifiedProfile verified;
        String currentMd5, gpu, gameVersion,previewProfile; long gameVersionCode; int screenWidth,screenHeight;
    }

    public static void main(String[] args) {
        System.out.println(RESULT_PREFIX+execute(args));
    }

    public static JSONObject execute(String[] args) {return new RootMain().run(args);}

    private JSONObject run(String[] args) {
        JSONObject response=new JSONObject();
        try {
            int uid=Process.myUid();
            diagnostics.put("workerUid",uid).put("privilege",uid==0?"root":uid==2000?"adb":"unprivileged");
            diagnostics.put("writeMode",uid==2000?"in-place-entry":"atomic-replace");
            if(uid!=0 && uid!=2000) throw new IOException("需要通过 Root 或 Shizuku 授权后访问游戏资源");
            if(args.length<1) throw new IOException("缺少操作参数");
            // Parse the closed package selector before any path or file access.
            // Requests retain their own immutable enum selection for the whole
            // transaction, independently of later UI selection changes.
            if("--package".equals(args[0])) {
                if(args.length<3) throw new IOException("缺少游戏包名或操作参数");
                game=GameTarget.forPackage(args[1]);
                args=Arrays.copyOfRange(args,2,args.length);
            }
            String action=args[0];
            if(!Arrays.asList("status","apply","official","undo","selftest").contains(action)) throw new IOException("未知操作");
            if(action.equals("selftest")) {
                response.put("ok",true).put("message",selfTest());
            } else if(action.equals("status")) {
                Session session=inspect();response=status(session).put("ok",true);
            } else {
                // Both su and Shizuku must lock the same external-storage
                // inode. A root-owned /data/local/tmp lock would prevent a
                // later shell worker from opening it after switching methods.
                FileAccessProbe.requireWritableDirectory(game.backupDirectory());
                try(RandomAccessFile lockFile=new RandomAccessFile(new File(game.backupDirectory(),".transaction.lock"),"rw");
                    FileLock lock=lockFile.getChannel().tryLock()) {
                    if(lock==null) throw new IOException("另一个修改正在进行，请稍后刷新");
                    writerLockHeld=true;
                    Session session=inspect();
                    byte[] replacement;
                    if(action.equals("apply")) {
                        if(args.length!=2) throw new IOException("缺少目标分辨率");
                        replacement=session.config.changeHighest(ResolutionSpec.forLongEdge(Integer.parseInt(args[1]),session.screenWidth,session.screenHeight));
                    } else if(action.equals("official")) replacement=session.verified.originalEntry();
                    else replacement=undoEntry(session);
                    if(Arrays.equals(session.entry,replacement)) {
                        response=status(session).put("ok",true).put("message","当前已是所选档位，无需重启");
                    } else {
                        // Probe creation and same-directory atomic rename
                        // before stopping the game. Shizuku's shell UID may
                        // be denied this path by a particular Android build.
                        FileAccessProbe.requireWritableDirectory(session.pack.getParentFile());
                        if(Process.myUid()==2000) {
                            try(RandomAccessFile writable=new RandomAccessFile(session.pack,"rw")) { }
                        }
                        session.verified.retainOriginal(game.backupDirectory());
                        stopGame();
                        ProfileConfig changedConfig=new ProfileConfig(replacement);
                        ResolutionSpec old=session.config.table(session.previewProfile).highest();
                        ResolutionSpec target=changedConfig.table(session.previewProfile).highest();
                        ResourceTransaction transaction=transaction(session.location,session.entry,replacement,session.currentMd5);
                        ResourceTransaction.Journal journal=new ResourceTransaction.Journal() {
                            JSONObject record;
                            public void prepared(File dir,String before,String after) throws IOException {
                                try {
                                    record=new JSONObject().put("status","prepared").put("action",action)
                                        .put("package",game.packageName).put("versionCode",session.gameVersionCode)
                                        .put("versionName",session.gameVersion)
                                        .put("writeMode",Process.myUid()==2000?"in-place-entry":"atomic-replace")
                                        .put("officialEntrySha256",Hashing.sha256(session.verified.originalEntry()))
                                        .put("packName",session.pack.getName()).put("packSize",session.location.packSize)
                                        .put("entryOffset",session.location.offset).put("entryLength",session.location.size)
                                        .put("beforeMd5",before).put("afterMd5",after)
                                        .put("beforeEntrySha256",Hashing.sha256(session.entry))
                                        .put("afterEntrySha256",Hashing.sha256(replacement))
                                        .put("beforeRectangle",new JSONArray(new int[]{old.width,old.height}))
                                        .put("afterRectangle",new JSONArray(new int[]{target.width,target.height}))
                                        .put("screen",new JSONArray(new int[]{session.screenWidth,session.screenHeight}))
                                        .put("profileChanges",profileChanges(session.config,changedConfig))
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
                            replacement,session.currentMd5,game.backupDirectory(),journal);
                        session.entry=replacement;session.currentMd5=changed.afterMd5;session.config=new ProfileConfig(replacement);
                        // Inspection captured the pre-write pack. Refresh the
                        // same candidate after commit so diagnostics describe
                        // the bytes just written, including restore and undo.
                        JSONArray inspected=diagnostics.getJSONArray("resourceCandidates");
                        for(int i=0;i<inspected.length();i++) {
                            JSONObject candidate=inspected.getJSONObject(i);
                            if(session.location.packMd5.equals(candidate.optString("officialPackMd5"))
                                    && candidate.optBoolean("verified")) {
                                candidate.put("currentPackMd5",session.currentMd5).put("entrySha256",Hashing.sha256(replacement));
                            }
                        }
                        boolean restarted=false;String launchError="";
                        try {
                            String launch=launchGame();
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
        try {
            response.put("diagnostics",diagnostics);
            if(args.length>0 && "status".equals(args[0]) && diagnostics.optBoolean("recoveredInterruptedWrite")) {
                response.put("message","已从中断备份恢复修改前配置，请手动启动游戏。");
            }
        } catch(JSONException impossible) {throw new AssertionError(impossible);}
        return response;
    }

    private Session inspect() throws Exception {
        String installed=command("cmd","package","list","packages","--show-versioncode",game.packageName);
        Matcher code=Pattern.compile("(?m)^package:"+Pattern.quote(game.packageName)+" versionCode:(\\d+)\\s*$").matcher(installed);
        if(!code.find()) throw new IOException("未找到"+game.label+"游戏安装信息："+installed.trim());
        long versionCode=Long.parseLong(code.group(1));
        Matcher versionName=Pattern.compile("(?m)^\\s*versionName=(\\S+)").matcher(command("dumpsys","package",game.packageName));
        String version=versionName.find()?versionName.group(1):"versionCode "+versionCode;
        // Version metadata is diagnostic, not a compatibility allowlist. The
        // current resource manifest and full binary decoder decide whether a
        // release can be edited, including releases unknown at build time.
        diagnostics.put("package",game.packageName).put("versionName",version).put("versionCode",versionCode);
        String gpu;
        try {
            JSONObject vulkan=new JSONObject(command("cmd","gpu","vkjson"));
            gpu=vulkan.getJSONArray("devices").getJSONObject(0).getJSONObject("properties").getString("deviceName");
        } catch(Exception error) {
            // GPU names only choose a preview table. Editing all explicit
            // tables must also work on OpenGL-only devices or Android builds
            // without vkjson; retain the actual detection failure for review.
            diagnostics.put("gpuDetectionError",error.toString());
            android.util.Log.w("DeviceInspection","GPU metadata unavailable; all-table editing remains available",error);
            gpu="未知 GPU";
        }
        diagnostics.put("gpu",gpu);
        String size=command("wm","size");
        ScreenGeometry geometry=ScreenGeometry.parse(size);
        int width=geometry.width,height=geometry.height;
        diagnostics.put("screen",new JSONArray(new int[]{width,height}));
        diagnostics.put("physicalScreen",new JSONArray(new int[]{geometry.physicalWidth,geometry.physicalHeight}))
            .put("displayOverride",geometry.overridden).put("wmSize",size.trim());
        List<File> manifests=new ArrayList<>();
        try(DirectoryStream<Path> paths=Files.newDirectoryStream(game.resourceDirectory().toPath())) {
            for(Path path:paths) if(path.getFileName().toString().matches("DesignV_[0-9a-f]{32}\\.bytes")) manifests.add(path.toFile());
        } catch(NoSuchFileException missing) {
            throw new IOException("请先启动"+game.label+"并完成游戏资源初始化："+game.resourceDirectory(),missing);
        } catch(IOException error) {
            throw new IOException("无法读取游戏资源目录（UID "+Process.myUid()+"）："+game.resourceDirectory()+"；"+error.getMessage(),error);
        }
        if(manifests.isEmpty()) throw new IOException("未找到资源清单，请先完成游戏下载");
        Session found=null;IOException last=null;int valid=0;
        JSONArray candidates=new JSONArray();diagnostics.put("resourceCandidates",candidates);
        for(File manifest:manifests) {
            JSONObject candidate=new JSONObject().put("manifest",manifest.getName());candidates.put(candidate);
            try {
                ResourceManifest.Entry location=ResourceManifest.locate(readLimited(manifest,16000000));
                candidate.put("officialPackMd5",location.packMd5).put("packSize",location.packSize)
                    .put("entryOffset",location.offset).put("entryLength",location.size);
                File pack=new File(game.resourceDirectory(),location.packMd5+".bytes");
                if(!pack.isFile() || pack.length()!=location.packSize) throw new IOException("资源包缺失或大小不符");
                // A partial write can still decode as valid dimensions. Check
                // the newest journal before schema/authenticity validation,
                // rather than treating every decodable resolution as complete.
                recoverInterruptedWrite(pack,location);
                byte[] entry=ResourceTransaction.readEntry(pack,location);
                candidate.put("entrySha256",Hashing.sha256(entry));
                VerifiedProfile verified=VerifiedProfile.inspect(pack,location,entry,game.backupDirectory());
                candidate.put("verified",true).put("currentPackMd5",verified.currentMd5);
                String previewProfile=verified.config.previewTableName(gpu);
                valid++;
                found=new Session();found.pack=pack;found.location=location;found.entry=entry;found.config=verified.config;
                found.verified=verified;found.gameVersion=version;found.gameVersionCode=versionCode;
                found.currentMd5=verified.currentMd5;found.gpu=gpu;found.screenWidth=width;found.screenHeight=height;
                found.previewProfile=previewProfile;
                candidate.put("previewProfile",found.previewProfile).put("editScope","all-explicit-resolution-tables");
            } catch(IOException error) {
                candidate.put("verified",false).put("error",error.getMessage());
                System.err.println("Resource inspection: "+candidate);last=error;
            }
        }
        if(found==null) throw last==null?new IOException("未找到可用的设备配置"):last;
        if(valid!=1) throw new IOException("存在多个有效资源清单，无法唯一定位当前配置");
        return found;
    }

    private JSONObject status(Session session) throws Exception {
        ProfileConfig.ResolutionTable preview=session.config.table(session.previewProfile);
        ResolutionSpec resolution=preview.highest();
        File previous=lastCompletedBackup(game.backupDirectory(),session.currentMd5);
        boolean undo=false;
        if(previous!=null) {
            JSONObject record=new JSONObject(new String(readLimited(new File(previous,"change.json"),32000),StandardCharsets.UTF_8));
            undo=record.optString("afterMd5").equals(session.currentMd5);
        }
        JSONArray table=new JSONArray();
        for(int i=0;i<preview.size();i++) {
            ResolutionSpec spec=preview.get(i);
            table.put(new JSONObject().put("width",spec.width).put("height",spec.height)
                .put("predicted",spec.predictedLabel(session.screenWidth,session.screenHeight)));
        }
        return new JSONObject().put("version",session.gameVersion).put("versionCode",session.gameVersionCode).put("gpu",session.gpu)
            .put("currentPackMd5",session.currentMd5).put("package",game.packageName).put("gameLabel",game.label)
            .put("screenWidth",session.screenWidth).put("screenHeight",session.screenHeight)
            .put("targetWidth",resolution.width).put("targetHeight",resolution.height)
            .put("previewProfile",session.previewProfile).put("previewProfileOnly",true)
            .put("highestGrade",preview.size()).put("profiles",profileChanges(session.config,session.config))
            .put("predicted",resolution.predictedLabel(session.screenWidth,session.screenHeight))
            .put("table",table).put("canUndo",undo).put("backupDirectory",game.backupDirectory().getAbsolutePath())
            .put("grade",savedResolutionGrade()).put("internal3dDirectlyMeasured",false);
    }

    private static JSONArray profileChanges(ProfileConfig before,ProfileConfig after) throws IOException,JSONException {
        JSONArray profiles=new JSONArray();
        for(ProfileConfig.ResolutionTable table:before.resolutionTables()) {
            ResolutionSpec old=table.highest(),target=after.table(table.name).highest();
            profiles.put(new JSONObject().put("name",table.name).put("highestGrade",table.size())
                .put("beforeRectangle",new JSONArray(new int[]{old.width,old.height}))
                .put("afterRectangle",new JSONArray(new int[]{target.width,target.height})));
        }
        return profiles;
    }

    private boolean recoverInterruptedWrite(File pack,ResourceManifest.Entry location) throws Exception {
        return recoverInterruptedWrite(pack,location,game.backupDirectory(),true);
    }

    private boolean recoverInterruptedWrite(File pack,ResourceManifest.Entry location,File backupRoot,boolean stopBeforeRecovery) throws Exception {
        File[] backups=backupRoot.listFiles(f->f.isDirectory()&&f.getName().startsWith("revert "));
        if(backups==null) return false;
        Arrays.sort(backups,Comparator.comparing(File::getName).reversed());
        String currentMd5=Hashing.md5(pack);
        for(File dir:backups) {
            JSONObject record;
            try {record=new JSONObject(new String(readLimited(new File(dir,"change.json"),32000),StandardCharsets.UTF_8));}
            catch(IOException | JSONException invalidRecord) {continue;}
            if(!record.optString("packName").equals(pack.getName())
                    || record.optLong("packSize")!=location.packSize || record.optLong("entryOffset")!=location.offset
                    || record.optInt("entryLength")!=location.size) continue;
            // Older prepared records can belong to already rolled-back failed
            // attempts. Never use one to undo a newer completed operation.
            if(!record.optString("status").equals("prepared") || !record.optString("writeMode").equals("in-place-entry")
                    || currentMd5.equals(record.optString("beforeMd5")) || currentMd5.equals(record.optString("afterMd5"))) return false;
            byte[] before=readLimited(new File(dir,"before-entry.bytes"),1000000);
            if(!Hashing.sha256(before).equals(record.optString("beforeEntrySha256"))
                    || !Hashing.substitutedMd5(pack,location.offset,before).equals(record.optString("beforeMd5"))) return false;
            // Never repair a partial entry while another process is still
            // writing it. Mutation requests already own this cross-UID lock;
            // a read/status request must acquire it before crash recovery.
            if(writerLockHeld) return restoreInterruptedEntry(pack,location,dir,record,before,stopBeforeRecovery);
            try(RandomAccessFile file=new RandomAccessFile(new File(backupRoot,".transaction.lock"),"rw");
                FileLock lock=file.getChannel().tryLock()) {
                if(lock==null) throw new IOException("另一个修改正在进行，请稍后刷新");
                return restoreInterruptedEntry(pack,location,dir,record,before,stopBeforeRecovery);
            }
        }
        return false;
    }

    private boolean restoreInterruptedEntry(File pack,ResourceManifest.Entry location,File dir,JSONObject record,byte[] before,boolean stopBeforeRecovery) throws Exception {
        String current=Hashing.md5(pack);
        if(current.equals(record.getString("beforeMd5")) || current.equals(record.getString("afterMd5"))) return true;
        if(!Hashing.substitutedMd5(pack,location.offset,before).equals(record.getString("beforeMd5"))) {
            throw new IOException("中断恢复前资源再次变化，已停止");
        }
        File interrupted=new File(dir,"interrupted-entry.bytes");
        if(!interrupted.exists()) ResourceTransaction.writeSynced(interrupted,ResourceTransaction.readEntry(pack,location));
        if(stopBeforeRecovery) stopGame();
        InPlaceEntryCommitter.recoverOriginal(pack,location,before,record.getString("beforeMd5"));
        record.put("status","rolled-back").put("recoveredAfterInterruption",true).put("interruptedMd5",current);
        writeJson(new File(dir,"change.json"),record);
        if(stopBeforeRecovery) diagnostics.put("recoveredInterruptedWrite",true).put("recoveryBackup",dir.getName());
        android.util.Log.w("ResourceRecovery","Restored interrupted entry from "+dir);
        return true;
    }

    private static ResourceTransaction transaction(ResourceManifest.Entry location,byte[] before,byte[] after,String beforeMd5) {
        return new ResourceTransaction(Process.myUid()==2000
            ?new InPlaceEntryCommitter(location,before,after,beforeMd5):RootMain::commitOnAndroid);
    }

    private int savedResolutionGrade() {
        // Only return the graphics grade; never serialize account preferences
        // or SDK payloads into diagnostics or backups.
        if(Process.myUid()!=0) return -1;
        try {
            File file=game.graphicsPreferences();
            String xml=new String(readLimited(file,1000000),StandardCharsets.UTF_8);
            Matcher value=Pattern.compile("<string name=\"GraphicsSettings_Model\">([^<]+)</string>").matcher(xml);
            if(value.find()) return new JSONObject(URLDecoder.decode(value.group(1),"UTF-8")).getInt("ResolutionQuality");
        } catch(Exception ignored) { }
        return -1;
    }

    private byte[] undoEntry(Session session) throws Exception {
        File previous=lastCompletedBackup(game.backupDirectory(),session.currentMd5);
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

    private String launchGame() throws IOException {
        String resolved=command("cmd","package","resolve-activity","--brief","-a","android.intent.action.MAIN",
            "-c","android.intent.category.LAUNCHER",game.packageName);
        Matcher component=Pattern.compile("(?m)^"+Pattern.quote(game.packageName)+"/[A-Za-z0-9_.$]+\\s*$").matcher(resolved);
        if(!component.find()) throw new IOException("无法解析"+game.label+"的启动入口："+resolved.trim());
        return command("am","start","-W","--windowingMode","1","-n",component.group().trim());
    }

    private void stopGame() throws IOException {
        command("am","force-stop",game.packageName);
        for(int attempt=0;attempt<20;attempt++) {
            java.lang.Process process=new ProcessBuilder("pidof",game.packageName).start();
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
        } catch(Exception error) {
            android.util.Log.e("ResourceCommit","Atomic resource replacement failed; uid="+Process.myUid()
                +", source="+prepared+", destination="+destination,error);
            throw new IOException("无法原子替换资源文件，原文件未被分段写入："+error.getMessage(),error);
        }
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

    private String selfTest() throws Exception {
        Session actual=inspect();
        FileAccessProbe.requireWritableDirectory(actual.pack.getParentFile());
        File scratch=new File(game.backupDirectory(),"selftest-"+UUID.randomUUID());
        if(!scratch.mkdirs()) throw new IOException("无法创建自检目录");
        try {
            // The production lock lives on shared external storage so both
            // privilege UIDs can open it. Verify that this Android filesystem
            // actually supports exclusive FileChannel locks before claiming
            // the isolated transaction path has passed its device selftest.
            try(RandomAccessFile lockFile=new RandomAccessFile(new File(scratch,".transaction.lock"),"rw");
                FileLock lock=lockFile.getChannel().tryLock()) {
                if(lock==null) throw new IOException("共享存储事务锁自检失败");
            }
            FileAccessProbe.requireWritableDirectory(scratch);
            byte[] before=actual.entry,after=actual.config.changeHighest(new ResolutionSpec(2880,1440));
            if(Arrays.equals(before,after)) after=actual.config.changeHighest(new ResolutionSpec(3414,1707));
            File fake=new File(scratch,"fixture.bytes");
            byte[] fakeBytes=new byte[before.length+64];Arrays.fill(fakeBytes,(byte)0x55);
            System.arraycopy(before,0,fakeBytes,32,before.length);ResourceTransaction.writeSynced(fake,fakeBytes);
            String originalMd5=Hashing.md5(fake);
            ResourceManifest.Entry location=new ResourceManifest.Entry(originalMd5,fake.length(),32,before.length);
            File scratchBackups=new File(scratch,"backups");
            VerifiedProfile.inspect(fake,location,before,scratchBackups).retainOriginal(scratchBackups);
            ResourceTransaction.Journal journal=new ResourceTransaction.Journal() {
                public void prepared(File dir,String b,String a) throws IOException {
                    try {writeJson(new File(dir,"change.json"),new JSONObject().put("status","prepared").put("beforeMd5",b).put("afterMd5",a));}
                    catch(JSONException e) {throw new IOException(e);}
                }
                // Deliberately retain the prepared journal to simulate loss
                // of the final status update after a successful atomic rename.
                public void completed(File dir,String a) { }
            };
            ResourceTransaction transaction=transaction(location,before,after,originalMd5);
            ResourceTransaction.Result applied=transaction.apply(fake,location,before,after,originalMd5,scratchBackups,journal);
            if(!Arrays.equals(ResourceTransaction.readEntry(fake,location),after)) throw new IOException("模拟写入自检失败");
            VerifiedProfile patched=VerifiedProfile.inspect(fake,location,after,scratchBackups);
            if(!Arrays.equals(patched.originalEntry(),before)) throw new IOException("当前资源原始配置恢复自检失败");
            File reconciled=lastCompletedBackup(scratchBackups,applied.afterMd5);
            if(reconciled==null || !reconciled.equals(applied.backup)) throw new IOException("中断后的回退记录恢复自检失败");
            transaction(location,after,patched.originalEntry(),applied.afterMd5)
                .apply(fake,location,after,patched.originalEntry(),applied.afterMd5,scratchBackups,journal);
            if(!Hashing.md5(fake).equals(originalMd5)) throw new IOException("模拟恢复自检失败");
            int changed=0;while(before[changed]==after[changed]) changed++;
            try(RandomAccessFile partial=new RandomAccessFile(fake,"rw")) {partial.seek(location.offset+changed);partial.writeByte(after[changed]);}
            File interrupted=new File(scratchBackups,"revert z-interrupted");
            if(!interrupted.mkdir()) throw new IOException("无法创建模拟中断记录");
            ResourceTransaction.writeSynced(new File(interrupted,"before-entry.bytes"),before);
            JSONObject fixtureRecord=new JSONObject().put("status","prepared").put("writeMode","in-place-entry")
                .put("packName",fake.getName()).put("packSize",location.packSize).put("entryOffset",location.offset)
                .put("entryLength",location.size).put("beforeMd5",originalMd5).put("afterMd5",applied.afterMd5)
                .put("beforeEntrySha256",Hashing.sha256(before));
            writeJson(new File(interrupted,"change.json"),fixtureRecord);
            if(!recoverInterruptedWrite(fake,location,scratchBackups,false) || !Hashing.md5(fake).equals(originalMd5)) {
                throw new IOException("中断条目与事务记录恢复自检失败");
            }
            File completed=new File(scratchBackups,"revert zz-completed");
            if(!completed.mkdir()) throw new IOException("无法创建模拟完成记录");
            writeJson(new File(completed,"change.json"),new JSONObject(fixtureRecord.toString()).put("status","completed"));
            try(RandomAccessFile later=new RandomAccessFile(fake,"rw")) {later.seek(location.offset);later.write(after);}
            if(recoverInterruptedWrite(fake,location,scratchBackups,false)) throw new IOException("旧中断记录覆盖了后续操作");
            return "真实资源检测与目录权限预检通过；独立副本的备份、写入、恢复及中断修复通过。未修改游戏资源或停止游戏。";
        } finally { deleteScratch(scratch); }
    }
    private static void deleteScratch(File file) throws IOException {
        if(file.isDirectory()) { File[] children=file.listFiles();if(children!=null) for(File child:children) deleteScratch(child); }
        Files.deleteIfExists(file.toPath());
    }
}
