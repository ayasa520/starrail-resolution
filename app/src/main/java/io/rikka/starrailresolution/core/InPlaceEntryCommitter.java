package io.rikka.starrailresolution.core;

import java.io.*;
import java.util.Arrays;

/** Preserves an app-owned inode when an ADB worker cannot chown a replacement. */
public final class InPlaceEntryCommitter implements ResourceTransaction.Committer {
    public interface Writer {void write(File file,long offset,byte[] bytes) throws IOException;}
    private static final Writer SYNCED_WRITER=(file,offset,bytes)-> {
        try(RandomAccessFile output=new RandomAccessFile(file,"rw")) {
            output.seek(offset);output.write(bytes);output.getFD().sync();
        }
    };
    private final ResourceManifest.Entry location;
    private final byte[] before,after;
    private final String beforeMd5;
    private final Writer writer;

    public InPlaceEntryCommitter(ResourceManifest.Entry location,byte[] before,byte[] after,String beforeMd5) {
        this(location,before,after,beforeMd5,SYNCED_WRITER);
    }
    public InPlaceEntryCommitter(ResourceManifest.Entry location,byte[] before,byte[] after,String beforeMd5,Writer writer) {
        this.location=location;this.before=before.clone();this.after=after.clone();this.beforeMd5=beforeMd5;this.writer=writer;
    }

    @Override public void replace(File prepared,File destination) throws IOException {
        if(!Hashing.md5(destination).equals(beforeMd5)
                || !Arrays.equals(ResourceTransaction.readEntry(destination,location),before)
                || !Arrays.equals(ResourceTransaction.readEntry(prepared,location),after)) {
            throw new IOException("定长写入前资源校验失败");
        }
        // The transaction has already synced the complete baseline, before/
        // after entries, and prepared journal. Change only the smallest byte
        // interval containing the differing resolution fields; replacing the
        // inode would lose its app UID, which shell is unable to restore.
        int first=0,last=before.length;
        while(first<last && before[first]==after[first]) first++;
        while(last>first && before[last-1]==after[last-1]) last--;
        try {
            writer.write(destination,location.offset+first,Arrays.copyOfRange(after,first,last));
            if(!Hashing.md5(destination).equals(Hashing.md5(prepared))) throw new IOException("定长写入后的完整资源校验失败");
        } catch(IOException failure) {
            try {recoverOriginal(destination,location,before,beforeMd5);}
            catch(IOException recovery) {
                failure.addSuppressed(recovery);
                throw new IOException("定长写入中断，原始配置备份已保留；请重新检测以恢复："+failure.getMessage(),failure);
            }
            throw new IOException("定长写入失败，已校验恢复修改前的资源："+failure.getMessage(),failure);
        }
    }

    public static void recoverOriginal(File pack,ResourceManifest.Entry location,byte[] before,String beforeMd5) throws IOException {
        // A crash can leave an undecodable varint. Recover from authenticated
        // entry bytes rather than decoding that damaged configuration first.
        // Never overwrite a game update or any change outside this entry.
        if(before.length!=location.size || pack.length()!=location.packSize
                || !Hashing.substitutedMd5(pack,location.offset,before).equals(beforeMd5)) {
            throw new IOException("中断资源的其余内容与备份不匹配，未覆盖");
        }
        SYNCED_WRITER.write(pack,location.offset,before);
        if(!Hashing.md5(pack).equals(beforeMd5)) throw new IOException("中断恢复后的完整资源校验失败");
    }
}
