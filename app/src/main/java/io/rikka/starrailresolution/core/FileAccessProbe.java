package io.rikka.starrailresolution.core;

import java.io.*;
import java.nio.file.*;

/** Verifies transactional storage operations without touching a game resource. */
public final class FileAccessProbe {
    public static void requireWritableDirectory(File directory) throws IOException {
        File source=null,destination=null;
        try {
            Files.createDirectories(directory.toPath());
            source=File.createTempFile(".resolution-access-",".tmp",directory);
            destination=new File(directory,source.getName()+".renamed");
            // Test the exact filesystem operations used by the transaction.
            // File.canWrite() cannot establish that an Android storage/FUSE
            // policy also permits creating and atomically replacing files.
            ResourceTransaction.writeSynced(source,new byte[]{0});
            Files.move(source.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE);
        } catch(IOException error) {
            throw new IOException("无法在此目录创建并原子替换文件："+directory+"；"+error.getMessage(),error);
        } finally {
            if(source!=null) Files.deleteIfExists(source.toPath());
            if(destination!=null) Files.deleteIfExists(destination.toPath());
        }
    }

    private FileAccessProbe() { }
}
