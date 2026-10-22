package io.rikka.starrailresolution.core;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Durable entry backups and verified, atomic replacement of a resource pack. */
public final class ResourceTransaction {
    public interface Committer { void replace(File prepared, File destination) throws IOException; }
    public interface Journal {
        void prepared(File directory, String beforeMd5, String afterMd5) throws IOException;
        void completed(File directory, String afterMd5) throws IOException;
    }
    public static final Committer FILE_COMMITTER = (prepared,destination) ->
        Files.move(prepared.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    public static final class Result {
        public final String beforeMd5, afterMd5;
        public final File backup;
        Result(String before, String after, File backup) { beforeMd5=before;afterMd5=after;this.backup=backup; }
    }

    private final Committer committer;
    public ResourceTransaction(Committer committer) { this.committer=committer; }

    public Result apply(File pack, ResourceManifest.Entry location, byte[] expectedBefore,
                        byte[] after, String expectedMd5, File backupRoot, Journal journal) throws IOException {
        if(expectedBefore.length!=location.size || after.length!=location.size || pack.length()!=location.packSize) {
            throw new IOException("资源包大小或替换范围不匹配");
        }
        if(!Hashing.md5(pack).equals(expectedMd5) || !Arrays.equals(readEntry(pack,location),expectedBefore)) {
            throw new IOException("资源在检测后发生变化，请重新检测");
        }
        if(Arrays.equals(expectedBefore,after)) throw new IOException("当前已经是所选分辨率，无需修改");
        String afterMd5=Hashing.substitutedMd5(pack,location.offset,after);
        if(!backupRoot.isDirectory() && !backupRoot.mkdirs()) throw new IOException("无法创建备份目录");
        String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.ROOT).format(new Date());
        File backup=new File(backupRoot,"revert "+stamp+"-"+UUID.randomUUID().toString().substring(0,8));
        if(!backup.mkdir()) throw new IOException("无法创建本次回退备份");
        writeSynced(new File(backup,"before-entry.bytes"),expectedBefore);
        writeSynced(new File(backup,"after-entry.bytes"),after);
        journal.prepared(backup,expectedMd5,afterMd5);

        // Keep one complete container snapshot per manifest generation, then
        // tiny before/after entry backups for every operation. A rollback only
        // needs this entry because the complete-container MD5 guards all other
        // bytes; the baseline snapshot also covers manual recovery scenarios.
        File baseline=new File(backupRoot,"baseline-"+location.packMd5+".bytes");
        if(!baseline.exists()) {
            File temporary=new File(backupRoot,baseline.getName()+".tmp");
            copySynced(pack,temporary);
            if(!Hashing.md5(temporary).equals(expectedMd5)) throw new IOException("完整备份校验失败，未写入游戏");
            Files.move(temporary.toPath(),baseline.toPath(),StandardCopyOption.ATOMIC_MOVE);
            writeSynced(new File(backupRoot,baseline.getName()+".md5"),(expectedMd5+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        File prepared=File.createTempFile(".resolution-", ".tmp",pack.getParentFile());
        try {
            copySynced(pack,prepared);
            try(RandomAccessFile file=new RandomAccessFile(prepared,"rw")) {
                file.seek(location.offset);file.write(after);file.getFD().sync();
            }
            if(prepared.length()!=location.packSize || !Hashing.md5(prepared).equals(afterMd5)) {
                throw new IOException("待写入文件校验失败，原文件未替换");
            }
            if(!Hashing.md5(pack).equals(expectedMd5)) throw new IOException("游戏资源已变化，取消替换");
            // The committer preserves original ownership/mode on Android and
            // renames within the same directory. A failed preparation or
            // rename leaves the original file intact instead of half patched.
            committer.replace(prepared,pack);
            if(pack.length()!=location.packSize || !Hashing.md5(pack).equals(afterMd5)) {
                throw new IOException("写入后的完整文件校验异常；回退备份已保留");
            }
            try { journal.completed(backup,afterMd5); }
            catch(IOException error) { throw new IOException("配置已写入并校验，但回退记录更新失败，请重新检测",error); }
            return new Result(expectedMd5,afterMd5,backup);
        } finally { Files.deleteIfExists(prepared.toPath()); }
    }

    public static byte[] readEntry(File pack, ResourceManifest.Entry location) throws IOException {
        if(location.offset<0 || location.size<1 || location.offset>pack.length()-location.size) throw new IOException("配置范围无效");
        byte[] bytes=new byte[location.size];
        try(RandomAccessFile file=new RandomAccessFile(pack,"r")) { file.seek(location.offset);file.readFully(bytes); }
        return bytes;
    }
    public static void writeSynced(File destination,byte[] bytes) throws IOException {
        try(FileOutputStream out=new FileOutputStream(destination)) { out.write(bytes);out.getFD().sync(); }
    }
    private static void copySynced(File source,File destination) throws IOException {
        try(InputStream in=new BufferedInputStream(new FileInputStream(source));FileOutputStream out=new FileOutputStream(destination)) {
            byte[] buffer=new byte[131072];int count;
            while((count=in.read(buffer))!=-1) out.write(buffer,0,count);
            out.getFD().sync();
        }
    }
}
