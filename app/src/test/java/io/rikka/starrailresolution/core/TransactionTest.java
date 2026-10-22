package io.rikka.starrailresolution.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class TransactionTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private File pack,backupRoot;private byte[] before,after,container;private ResourceManifest.Entry location;private String beforeMd5;
    private final ResourceTransaction.Journal journal=new ResourceTransaction.Journal() {
        public void prepared(File dir,String a,String b) throws IOException {ResourceTransaction.writeSynced(new File(dir,"journal"),(a+" "+b).getBytes(StandardCharsets.UTF_8));}
        public void completed(File dir,String after) throws IOException {ResourceTransaction.writeSynced(new File(dir,"complete"),after.getBytes(StandardCharsets.UTF_8));}
    };
    @Before public void prepare() throws Exception {
        before=ConfigTest.resource("Android-original.bytes");after=new ProfileConfig(before).changeHighest(new ResolutionSpec(3414,1707));
        container=new byte[before.length+300000];new Random(7).nextBytes(container);System.arraycopy(before,0,container,131050,before.length);
        pack=folder.newFile("fixture.bytes");Files.write(pack.toPath(),container);
        beforeMd5=Hashing.md5(pack);location=new ResourceManifest.Entry(beforeMd5,pack.length(),131050,before.length);
        backupRoot=new File(folder.getRoot(),"backups");
    }
    @Test public void writesAndRestoresAcrossHashBufferBoundary() throws Exception {
        ResourceTransaction tx=new ResourceTransaction(ResourceTransaction.FILE_COMMITTER);
        ResourceTransaction.Result applied=tx.apply(pack,location,before,after,beforeMd5,backupRoot,journal);
        assertArrayEquals(after,ResourceTransaction.readEntry(pack,location));
        assertArrayEquals(before,Files.readAllBytes(new File(applied.backup,"before-entry.bytes").toPath()));
        assertEquals(container.length,pack.length());
        byte[] current=Files.readAllBytes(pack.toPath());
        for(int i=0;i<container.length;i++) if(i<location.offset||i>=location.offset+before.length) assertEquals(container[i],current[i]);
        tx.apply(pack,location,after,before,applied.afterMd5,backupRoot,journal);
        assertArrayEquals(container,Files.readAllBytes(pack.toPath()));
    }
    @Test public void failedCommitLeavesOriginalIntact() throws Exception {
        ResourceTransaction tx=new ResourceTransaction((a,b)->{throw new IOException("simulated rename failure");});
        assertThrows(IOException.class,()->tx.apply(pack,location,before,after,beforeMd5,backupRoot,journal));
        assertArrayEquals(container,Files.readAllBytes(pack.toPath()));
        assertEquals(0,Objects.requireNonNull(pack.getParentFile().listFiles(f->f.getName().endsWith(".tmp"))).length);
    }
    @Test public void staleInputIsNeverOverwritten() throws Exception {
        byte[] different=container.clone();different[0]^=1;Files.write(pack.toPath(),different);
        ResourceTransaction tx=new ResourceTransaction(ResourceTransaction.FILE_COMMITTER);
        assertThrows(IOException.class,()->tx.apply(pack,location,before,after,beforeMd5,backupRoot,journal));
        assertArrayEquals(different,Files.readAllBytes(pack.toPath()));
    }
    @Test public void backupFailureStopsBeforeChangingGameFile() throws Exception {
        ResourceTransaction.Journal failing=new ResourceTransaction.Journal() {
            public void prepared(File d,String b,String a) throws IOException {throw new IOException("disk full");}
            public void completed(File d,String a) {}
        };
        assertThrows(IOException.class,()->new ResourceTransaction(ResourceTransaction.FILE_COMMITTER).apply(pack,location,before,after,beforeMd5,backupRoot,failing));
        assertArrayEquals(container,Files.readAllBytes(pack.toPath()));
    }
}
