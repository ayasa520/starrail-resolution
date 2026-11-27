package io.rikka.starrailresolution.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static org.junit.Assert.*;

public class InPlaceEntryCommitterTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private File pack,prepared;
    private byte[] original,after,container;
    private String md5;
    private ResourceManifest.Entry location;
    private int first;

    @Before public void prepare() throws Exception {
        original=ConfigTest.resource("Android-4.7.0.bytes");
        after=new ProfileConfig(original).changeHighest(new ResolutionSpec(5120,2560));
        container=new byte[original.length+64];Arrays.fill(container,(byte)0x55);
        System.arraycopy(original,0,container,32,original.length);
        pack=folder.newFile();Files.write(pack.toPath(),container);md5=Hashing.md5(pack);
        location=new ResourceManifest.Entry(md5,pack.length(),32,original.length);
        byte[] changed=container.clone();System.arraycopy(after,0,changed,32,after.length);
        prepared=folder.newFile();Files.write(prepared.toPath(),changed);
        while(original[first]==after[first]) first++;
    }

    @Test public void preservesInodeAndEveryUnrelatedByte() throws Exception {
        Object inode=Files.readAttributes(pack.toPath(),BasicFileAttributes.class).fileKey();
        Object owner=Files.getOwner(pack.toPath());
        new InPlaceEntryCommitter(location,original,after,md5).replace(prepared,pack);
        assertEquals(inode,Files.readAttributes(pack.toPath(),BasicFileAttributes.class).fileKey());
        assertEquals(owner,Files.getOwner(pack.toPath()));
        assertArrayEquals(Files.readAllBytes(prepared.toPath()),Files.readAllBytes(pack.toPath()));
    }

    @Test public void partialIoFailureRestoresTheVerifiedOriginal() throws Exception {
        InPlaceEntryCommitter.Writer failing=(file,offset,bytes)-> {
            try(RandomAccessFile out=new RandomAccessFile(file,"rw")) {out.seek(offset);out.writeByte(bytes[0]);}
            throw new IOException("simulated short write");
        };
        assertThrows(IOException.class,()->new InPlaceEntryCommitter(location,original,after,md5,failing).replace(prepared,pack));
        assertArrayEquals(container,Files.readAllBytes(pack.toPath()));
    }

    @Test public void recoversInterruptedUndecodableEntryFromItsBackup() throws Exception {
        try(RandomAccessFile out=new RandomAccessFile(pack,"rw")) {out.seek(location.offset);out.writeByte(255);}
        assertThrows(IOException.class,()->new ProfileConfig(ResourceTransaction.readEntry(pack,location)));
        InPlaceEntryCommitter.recoverOriginal(pack,location,original,md5);
        assertArrayEquals(container,Files.readAllBytes(pack.toPath()));
    }

    @Test public void neverRecoversAcrossAnUnrelatedResourceChange() throws Exception {
        try(RandomAccessFile out=new RandomAccessFile(pack,"rw")) {out.writeByte(0);out.seek(location.offset+first);out.writeByte(after[first]);}
        byte[] changed=Files.readAllBytes(pack.toPath());
        assertThrows(IOException.class,()->InPlaceEntryCommitter.recoverOriginal(pack,location,original,md5));
        assertArrayEquals(changed,Files.readAllBytes(pack.toPath()));
    }
}
