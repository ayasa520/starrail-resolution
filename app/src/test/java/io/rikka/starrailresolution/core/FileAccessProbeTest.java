package io.rikka.starrailresolution.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class FileAccessProbeTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    @Test public void successfulProbePreservesExistingFilesAndRemovesScratchFiles() throws Exception {
        File directory=folder.newFolder();File resource=new File(directory,"game.bytes");
        byte[] original={1,2,3,4};Files.write(resource.toPath(),original);
        FileAccessProbe.requireWritableDirectory(directory);
        assertArrayEquals(original,Files.readAllBytes(resource.toPath()));
        assertArrayEquals(new String[]{"game.bytes"},directory.list());
    }

    @Test public void reportsUnusablePathWithoutOverwritingExistingFile() throws Exception {
        File resource=folder.newFile();byte[] original={1,2,3,4};Files.write(resource.toPath(),original);
        IOException error=assertThrows(IOException.class,()->FileAccessProbe.requireWritableDirectory(resource));
        assertTrue(error.getMessage().contains(resource.getPath()));
        assertArrayEquals(original,Files.readAllBytes(resource.toPath()));
    }
}
