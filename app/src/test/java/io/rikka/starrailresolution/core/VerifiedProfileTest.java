package io.rikka.starrailresolution.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class VerifiedProfileTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private File pack,backups;
    private byte[] official;
    private ResourceManifest.Entry location;

    private void prepare(byte[] original) throws Exception {
        official=original;
        byte[] container=new byte[official.length+64];Arrays.fill(container,(byte)0x55);
        System.arraycopy(official,0,container,32,official.length);
        pack=folder.newFile();Files.write(pack.toPath(),container);
        location=new ResourceManifest.Entry(Hashing.md5(pack),pack.length(),32,official.length);
        backups=new File(folder.getRoot(),"backups");
    }

    private VerifiedProfile inspect() throws IOException {
        return VerifiedProfile.inspect(pack,location,ResourceTransaction.readEntry(pack,location),backups);
    }

    private void writeEntry(byte[] entry) throws IOException {
        try(RandomAccessFile file=new RandomAccessFile(pack,"rw")) {file.seek(location.offset);file.write(entry);}
    }

    @Test public void acceptsActual470ResourceAndKeepsItsOriginalAcrossEdits() throws Exception {
        prepare(ConfigTest.resource("Android-4.7.0.bytes"));
        assertNotEquals(ProfileConfig.OFFICIAL_SHA256,Hashing.sha256(official));
        VerifiedProfile first=inspect();first.retainOriginal(backups);
        ResolutionSpec target=ResolutionSpec.forLongEdge(2560,3840,2560);
        writeEntry(first.config.changeHighest(target));
        VerifiedProfile edited=inspect();
        assertEquals(target,edited.config.resolution("Adreno VeryHigh",4));
        assertArrayEquals(official,edited.originalEntry());
        for(int i=0;i<4;i++) assertEquals(first.config.resolution("Adreno VeryHigh",i),edited.config.resolution("Adreno VeryHigh",i));
        writeEntry(edited.originalEntry());
        assertEquals(location.packMd5,inspect().currentMd5);
    }

    @Test public void restoresThisGenerationsDimensionsInsteadOfLegacyConstants() throws Exception {
        byte[] differentOriginal=new ProfileConfig(ConfigTest.resource("Android-4.7.0.bytes"))
            .changeHighest(new ResolutionSpec(2400,1200));
        prepare(differentOriginal);
        VerifiedProfile first=inspect();first.retainOriginal(backups);
        writeEntry(first.config.changeHighest(new ResolutionSpec(3414,1707)));
        assertArrayEquals(differentOriginal,inspect().originalEntry());
        assertEquals(new ResolutionSpec(2400,1200),new ProfileConfig(inspect().originalEntry()).resolution("Adreno VeryHigh",4));
    }

    @Test public void acceptsLegacyEditsWithoutANewBaseline() throws Exception {
        prepare(ConfigTest.resource("Android-original.bytes"));
        writeEntry(new ProfileConfig(official).changeHighest("Adreno VeryHigh",new ResolutionSpec(3414,1707)));
        assertArrayEquals(official,inspect().originalEntry());
    }

    @Test public void acceptsVerifiedFullBackupFromEarlierHelper() throws Exception {
        prepare(ConfigTest.resource("Android-4.7.0.bytes"));
        assertTrue(backups.mkdir());
        Files.copy(pack.toPath(),new File(backups,"baseline-"+location.packMd5+".bytes").toPath());
        writeEntry(new ProfileConfig(official).changeHighest("Adreno VeryHigh",new ResolutionSpec(3414,1707)));
        assertArrayEquals(official,inspect().originalEntry());
    }

    @Test public void rejectsNewerEditedResourcesWithNoAuthenticatedOriginal() throws Exception {
        prepare(ConfigTest.resource("Android-4.7.0.bytes"));
        writeEntry(new ProfileConfig(official).changeHighest("Adreno VeryHigh",new ResolutionSpec(3414,1707)));
        IOException failure=assertThrows(IOException.class,this::inspect);
        assertTrue(failure.getMessage().contains(location.packMd5));
    }

    @Test public void refusesUnrelatedChangesInsideOrOutsideTheEntry() throws Exception {
        prepare(ConfigTest.resource("Android-4.7.0.bytes"));
        inspect().retainOriginal(backups);
        byte[] unrelated=official.clone();unrelated[35]^=1;writeEntry(unrelated);
        assertThrows(IOException.class,this::inspect);
        writeEntry(official);
        try(RandomAccessFile file=new RandomAccessFile(pack,"rw")) {file.seek(0);file.writeByte(0);}
        assertThrows(IOException.class,this::inspect);
    }

    @Test public void refusesConflictingOriginalBackup() throws Exception {
        prepare(ConfigTest.resource("Android-4.7.0.bytes"));
        VerifiedProfile verified=inspect();verified.retainOriginal(backups);
        File original=new File(backups,"official-entry-"+location.packMd5+".bytes");
        byte[] corrupt=Files.readAllBytes(original.toPath());corrupt[0]^=1;Files.write(original.toPath(),corrupt);
        assertThrows(IOException.class,()->verified.retainOriginal(backups));
        assertEquals(location.packMd5,Hashing.md5(pack));
    }

    @Test public void unknownFieldsReportTheirTypeAndMask() throws Exception {
        byte[] bytes=ConfigTest.resource("Android-4.7.0.bytes");
        // Root has six known fields. Setting bit six changes only its mask,
        // so the decoder must explain the new schema before reading payloads.
        bytes[0]|=64;
        IOException failure=assertThrows(IOException.class,()->new ProfileConfig(bytes));
        assertTrue(failure.getMessage().contains("Root"));
        assertTrue(failure.getMessage().contains("0x"));
    }
}
