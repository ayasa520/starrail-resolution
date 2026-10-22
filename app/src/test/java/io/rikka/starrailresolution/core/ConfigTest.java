package io.rikka.starrailresolution.core;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class ConfigTest {
    static byte[] resource(String name) throws IOException {
        try(InputStream in=ConfigTest.class.getResourceAsStream("/"+name)) {
            if(in==null) throw new IOException(name);
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1) out.write(b,0,n);return out.toByteArray();
        }
    }
    @Test public void locatesActualResourceWithoutFixedOffset() throws Exception {
        ResourceManifest.Entry entry=ResourceManifest.locate(resource("DesignV.bytes"));
        assertEquals("98c54c7035b131b7dc27c3466346021d",entry.packMd5);
        assertEquals(128700379,entry.packSize);assertEquals(38253341,entry.offset);assertEquals(4252,entry.size);
    }
    @Test public void reproducesAllThreeVerifiedAdbPatchesExactly() throws Exception {
        byte[] original=resource("Android-original.bytes");
        ProfileConfig config=new ProfileConfig(original);
        int[][] rectangles={{2880,1440},{4266,2133},{3414,1707}};
        String[] hashes={"df6b3e8536364c97e49037083eebbb264777f43b9418bfea21ea667a8f196b9f",
            "9ad4dedfedd61c706d58b6277c2cf3d1bcd2e5106cfe40e8f86c33932cdbc26a",
            "bebb3b31c4cc860cf91c7db7863fa159b142b11bbf77ef802a0d03f53d954f55"};
        for(int i=0;i<rectangles.length;i++) {
            byte[] changed=config.changeHighest(new ResolutionSpec(rectangles[i][0],rectangles[i][1]));
            assertEquals(hashes[i],Hashing.sha256(changed));
            assertArrayEquals(original,new ProfileConfig(changed).originalEntry());
            int differences=0;
            for(int p=0;p<original.length;p++) if(original[p]!=changed[p]) differences++;
            assertEquals(4,differences);
        }
    }
    @Test public void refusesUnrelatedConfigEdits() throws Exception {
        byte[] bytes=resource("Android-original.bytes");bytes[35]^=1;
        assertThrows(IOException.class,()->new ProfileConfig(bytes));
    }
    @Test public void refusesTruncatedOrNewSchemaData() throws Exception {
        byte[] bytes=resource("Android-original.bytes");
        assertThrows(IOException.class,()->new ProfileConfig(Arrays.copyOf(bytes,bytes.length-1)));
        assertThrows(IOException.class,()->ResourceManifest.locate(Arrays.copyOf(resource("DesignV.bytes"),300)));
    }
    @Test public void refusesDuplicateResourceHashes() throws Exception {
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(buffer);
        out.writeInt(255);out.writeInt(4);out.writeInt(1);out.writeInt(2);
        out.writeLong(1);out.write(new byte[16]);out.writeLong(10000);out.writeInt(2);
        for(int i=0;i<2;i++) {out.writeLong(ResourceManifest.DEVICE_CONFIG_HASH);out.writeInt(4252);out.writeInt(0);}
        out.writeShort(0);out.writeByte(128);
        assertThrows(IOException.class,()->ResourceManifest.locate(buffer.toByteArray()));
    }
    @Test public void validatesCustomAndNativeTargets() throws Exception {
        assertEquals(new ResolutionSpec(3414,1707),ResolutionSpec.forLongEdge(2560,3840,2560));
        assertEquals(new ResolutionSpec(2880,1440),ResolutionSpec.forLongEdge(2160,3840,2560));
        assertEquals(new ResolutionSpec(5120,2560),ResolutionSpec.forLongEdge(3840,3840,2560));
        assertThrows(IOException.class,()->ResolutionSpec.forLongEdge(9000,3840,2560));
        assertThrows(IOException.class,()->ResolutionSpec.forLongEdge(0,3840,2560));
    }
}
