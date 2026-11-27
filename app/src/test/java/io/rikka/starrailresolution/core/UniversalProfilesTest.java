package io.rikka.starrailresolution.core;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class UniversalProfilesTest {
    @Test public void editsAllActualGpuTablesAndInheritedDefaultWithoutChangingLowerGrades() throws Exception {
        byte[] source=ConfigTest.resource("Android-4.7.0.bytes");
        ProfileConfig original=new ProfileConfig(source);
        ResolutionSpec target=ResolutionSpec.forLongEdge(3840,3840,2560);
        ProfileConfig edited=new ProfileConfig(original.changeHighest(target));
        assertEquals(3,original.resolutionTables().size());
        assertEquals(4,original.table("Default").size());
        assertEquals(5,original.table("Mali VeryHigh").size());
        for(ProfileConfig.ResolutionTable table:original.resolutionTables()) {
            ProfileConfig.ResolutionTable changed=edited.table(table.name);
            assertEquals(target,changed.highest());
            for(int i=0;i<table.size()-1;i++) assertEquals(table.get(i),changed.get(i));
        }
        assertArrayEquals(source,edited.restoreResolutions(original));
    }

    @Test public void newGpuProfileNamesNeedNoCodeAllowlist() throws Exception {
        byte[] source=ConfigTest.resource("Android-4.7.0.bytes");
        byte[] before="Mali VeryHigh".getBytes(StandardCharsets.UTF_8);
        byte[] after="Next VeryHigh".getBytes(StandardCharsets.UTF_8);
        int found=-1;
        for(int i=0;i<=source.length-before.length;i++) {
            boolean match=true;for(int j=0;j<before.length;j++) match&=source[i+j]==before[j];
            if(match) {found=i;break;}
        }
        assertTrue(found>=0);System.arraycopy(after,0,source,found,after.length);
        ProfileConfig config=new ProfileConfig(source);
        ResolutionSpec target=new ResolutionSpec(2400,1080);
        ProfileConfig changed=new ProfileConfig(config.changeHighest(target));
        assertEquals(target,changed.table("Next VeryHigh").highest());
        assertEquals(target,changed.table("Default").highest());
    }

    @Test public void previewRulesDoNotRestrictWhichTablesAreEdited() throws Exception {
        ProfileConfig config=new ProfileConfig(ConfigTest.resource("Android-4.7.0.bytes"));
        assertEquals("Adreno VeryHigh",config.previewTableName("Adreno (TM) 750"));
        assertEquals("Mali VeryHigh",config.previewTableName("Immortalis-G925"));
        assertEquals("Mali VeryHigh",config.previewTableName("Xclipse 950"));
        assertEquals("Default",config.previewTableName("Maleoon 920"));
        assertEquals("Default",config.previewTableName("PowerVR Rogue GE8320"));
        assertEquals("Default",config.previewTableName("Unknown next-generation GPU"));
    }

    @Test public void refusesSizeChangingEditsBeforeReturningAnyModifiedEntry() throws Exception {
        byte[] source=ConfigTest.resource("Android-4.7.0.bytes"),snapshot=source.clone();
        IOException failure=assertThrows(IOException.class,()->new ProfileConfig(source).changeHighest(new ResolutionSpec(8192,4320)));
        assertTrue(failure.getMessage().contains("偏移"));
        assertArrayEquals(snapshot,source);
    }

    @Test public void targetsFollowUltrawideSquareAndPortraitDisplayDimensions() throws Exception {
        for(int[] size:new int[][]{{2400,1080},{1080,2400},{2208,1840},{1920,1920},{3840,2560}}) {
            int longEdge=Math.max(size[0],size[1]),shortEdge=Math.min(size[0],size[1]);
            assertEquals(new ResolutionSpec(longEdge,shortEdge),ResolutionSpec.forLongEdge(longEdge,size[0],size[1]));
            ResolutionSpec half=ResolutionSpec.forLongEdge(longEdge/2,size[0],size[1]);
            assertEquals(longEdge/2,half.width);
            assertEquals((int)Math.round((double)(longEdge/2)*shortEdge/longEdge),half.height);
            int[] presets=ResolutionSpec.presetLongEdges(longEdge);
            assertEquals(longEdge,presets[presets.length-1]);
            for(int value:presets) assertTrue(value>=ResolutionSpec.minimumLongEdge(longEdge)&&value<=longEdge);
        }
    }

    @Test public void smallDisplaysAndInvalidDimensionsHaveExplicitBounds() throws Exception {
        assertEquals(new ResolutionSpec(640,480),ResolutionSpec.forLongEdge(640,640,480));
        assertArrayEquals(new int[]{640},ResolutionSpec.presetLongEdges(640));
        int[] presets=ResolutionSpec.presetLongEdges(1280);
        for(int i=1;i<presets.length;i++) assertTrue(presets[i]>presets[i-1]);
        assertThrows(IOException.class,()->ResolutionSpec.forLongEdge(720,0,1080));
        assertThrows(IOException.class,()->ResolutionSpec.forLongEdge(719,2400,1080));
    }

    @Test public void parsesActiveOverrideAndKeepsPhysicalDimensionsForDiagnostics() throws Exception {
        ScreenGeometry nativeSize=ScreenGeometry.parse("Physical size: 2560x3840\n");
        assertEquals(3840,nativeSize.width);assertEquals(2560,nativeSize.height);assertFalse(nativeSize.overridden);
        ScreenGeometry overridden=ScreenGeometry.parse("Physical size: 1440x3200\nOverride size: 1080x2400\n");
        assertEquals(2400,overridden.width);assertEquals(1080,overridden.height);
        assertEquals(3200,overridden.physicalWidth);assertTrue(overridden.overridden);
        assertThrows(IOException.class,()->ScreenGeometry.parse("Physical size: 0x2400"));
        assertThrows(IOException.class,()->ScreenGeometry.parse("display size unavailable"));
    }
}
