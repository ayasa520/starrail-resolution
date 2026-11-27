package io.rikka.starrailresolution.core;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameTargetTest {
    @Test public void identifiesActualCountryAndInternationalInstallations() throws Exception {
        assertEquals(GameTarget.CHINA,GameTarget.forPackage("com.miHoYo.hkrpg"));
        assertEquals(GameTarget.INTERNATIONAL,GameTarget.forPackage("com.HoYoverse.hkrpgoversea"));
        assertEquals("国服",GameTarget.CHINA.label);
    }

    @Test public void keepsResourcePreferencesAndUndoNamespacesInsideSelectedPackage() {
        assertEquals("/sdcard/Android/data/com.miHoYo.hkrpg/files/DesignData/Android",GameTarget.CHINA.resourceDirectory().getPath());
        assertEquals("/data/user/0/com.miHoYo.hkrpg/shared_prefs/com.miHoYo.hkrpg.v2.playerprefs.xml",GameTarget.CHINA.graphicsPreferences().getPath());
        assertEquals("/sdcard/Download/StarRailResolution",GameTarget.INTERNATIONAL.backupDirectory().getPath());
        assertEquals(GameTarget.INTERNATIONAL.backupDirectory(),GameTarget.CHINA.backupDirectory().getParentFile());
        assertNotEquals(GameTarget.INTERNATIONAL.backupDirectory(),GameTarget.CHINA.backupDirectory());
    }

    @Test public void rejectsCallerSuppliedPackagePaths() {
        for(String value:new String[]{null,"","com.other.game","../../data/system","com.miHoYo.hkrpg/../../other"}) {
            assertThrows(IOException.class,()->GameTarget.forPackage(value));
        }
    }
}
