package io.rikka.starrailresolution.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Authenticates a resource generation and retains its own original resolution table. */
public final class VerifiedProfile {
    public final ProfileConfig config;
    public final String currentMd5;
    private final byte[] officialEntry;
    private final ResourceManifest.Entry location;

    private VerifiedProfile(ProfileConfig config,String currentMd5,byte[] official,ResourceManifest.Entry location) {
        this.config=config;this.currentMd5=currentMd5;this.officialEntry=official.clone();this.location=location;
    }

    public static VerifiedProfile inspect(File pack,ResourceManifest.Entry location,byte[] entry,File backups) throws IOException {
        if(pack.length()!=location.packSize || entry.length!=location.size) throw new IOException("资源包或配置长度不符");
        ProfileConfig config=new ProfileConfig(entry);
        String currentMd5=Hashing.md5(pack);
        if(currentMd5.equals(location.packMd5)) return new VerifiedProfile(config,currentMd5,entry,location);

        // A release number says nothing about this binary schema. Authenticate
        // the complete current pack after restoring only the known resolution
        // fields from an original entry belonging to this manifest generation.
        // Every other byte, including other GPU profiles, must remain official.
        List<byte[]> candidates=new ArrayList<>();
        File saved=officialFile(backups,location);
        if(saved.isFile() && saved.length()==location.size) candidates.add(Files.readAllBytes(saved.toPath()));
        File fullBaseline=new File(backups,"baseline-"+location.packMd5+".bytes");
        if(fullBaseline.isFile() && fullBaseline.length()==location.packSize) {
            candidates.add(ResourceTransaction.readEntry(fullBaseline,location));
        }
        try {candidates.add(config.legacyOriginalEntry());} catch(IOException ignored) { }
        for(byte[] official:candidates) {
            try {
                byte[] normalized=config.restoreResolutions(new ProfileConfig(official));
                if(Arrays.equals(normalized,official)
                        && Hashing.substitutedMd5(pack,location.offset,normalized).equals(location.packMd5)) {
                    return new VerifiedProfile(config,currentMd5,official,location);
                }
            } catch(IOException ignored) { }
        }
        throw new IOException("资源包校验未通过，且没有匹配的原始配置备份；当前 MD5="+currentMd5
            +"，清单 MD5="+location.packMd5+"。请使用游戏资源修复后重新检测");
    }

    public byte[] originalEntry() {return officialEntry.clone();}

    public void retainOriginal(File backups) throws IOException {
        if(!backups.isDirectory() && !backups.mkdirs()) throw new IOException("无法创建原始配置备份目录");
        File destination=officialFile(backups,location);
        if(destination.exists()) {
            if(destination.length()!=officialEntry.length
                    || !Arrays.equals(Files.readAllBytes(destination.toPath()),officialEntry)) {
                throw new IOException("原始配置备份与当前官方资源不符，未写入游戏");
            }
            return;
        }
        // Persist the authenticated original before any game write. A killed
        // process can then identify/restore a patched newer release without
        // relying on an old release's hard-coded dimensions or fingerprint.
        File temporary=File.createTempFile(".official-entry-",".tmp",backups);
        try {
            ResourceTransaction.writeSynced(temporary,officialEntry);
            Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE);
        } finally {Files.deleteIfExists(temporary.toPath());}
    }

    private static File officialFile(File backups,ResourceManifest.Entry location) {
        return new File(backups,"official-entry-"+location.packMd5+".bytes");
    }
}
