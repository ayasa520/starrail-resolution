package io.rikka.starrailresolution.core;

import java.io.File;
import java.io.IOException;

/** Closed set of game installations accepted by privileged file operations. */
public enum GameTarget {
    INTERNATIONAL("com.HoYoverse.hkrpgoversea","国际服"),
    CHINA("com.miHoYo.hkrpg","国服");

    public final String packageName,label;
    GameTarget(String packageName,String label) {this.packageName=packageName;this.label=label;}

    public static GameTarget forPackage(String packageName) throws IOException {
        for(GameTarget target:values()) if(target.packageName.equals(packageName)) return target;
        throw new IOException("未识别的游戏包名："+packageName);
    }

    public File resourceDirectory() {
        return new File("/sdcard/Android/data/"+packageName+"/files/DesignData/Android");
    }

    public File backupDirectory() {
        File root=new File("/sdcard/Download/StarRailResolution");
        // Keep the historical international namespace so existing originals
        // and undo journals remain usable. Country variants get independent
        // journals even when both installations ship identical resource hashes.
        return this==INTERNATIONAL?root:new File(root,packageName);
    }

    public File graphicsPreferences() {
        return new File("/data/user/0/"+packageName+"/shared_prefs/"+packageName+".v2.playerprefs.xml");
    }
}
