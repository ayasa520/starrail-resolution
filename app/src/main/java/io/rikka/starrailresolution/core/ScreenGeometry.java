package io.rikka.starrailresolution.core;

import java.io.IOException;
import java.util.regex.*;

/** Parses the active display dimensions without changing any system setting. */
public final class ScreenGeometry {
    public final int width,height,physicalWidth,physicalHeight;
    public final boolean overridden;

    private ScreenGeometry(int a,int b,int physicalA,int physicalB,boolean overridden) throws IOException {
        if(Math.min(a,b)<=0 || Math.min(physicalA,physicalB)<=0) throw new IOException("系统报告的屏幕尺寸无效");
        width=Math.max(a,b);height=Math.min(a,b);
        physicalWidth=Math.max(physicalA,physicalB);physicalHeight=Math.min(physicalA,physicalB);
        this.overridden=overridden;
    }

    public static ScreenGeometry parse(String output) throws IOException {
        Matcher physical=Pattern.compile("Physical size: (\\d+)x(\\d+)").matcher(output);
        if(!physical.find()) throw new IOException("无法读取屏幕尺寸："+output.trim());
        try {
            int a=Integer.parseInt(physical.group(1)),b=Integer.parseInt(physical.group(2));
            Matcher override=Pattern.compile("Override size: (\\d+)x(\\d+)").matcher(output);
            // Android exposes the override as the app's active full-screen
            // coordinate space. Preserve the physical size in diagnostics,
            // but calculate render targets against the active dimensions.
            return override.find()?new ScreenGeometry(Integer.parseInt(override.group(1)),
                Integer.parseInt(override.group(2)),a,b,true):new ScreenGeometry(a,b,a,b,false);
        } catch(NumberFormatException invalid) {throw new IOException("系统屏幕尺寸超出整数范围",invalid);}
    }
}
