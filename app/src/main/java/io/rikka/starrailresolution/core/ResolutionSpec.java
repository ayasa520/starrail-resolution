package io.rikka.starrailresolution.core;

import java.io.IOException;

public final class ResolutionSpec {
    public final int width, height;
    public ResolutionSpec(int width, int height) { this.width = width; this.height = height; }

    public static ResolutionSpec forLongEdge(int pixels, int screenWidth, int screenHeight) throws IOException {
        int longEdge=Math.max(screenWidth,screenHeight),shortEdge=Math.min(screenWidth,screenHeight);
        if(shortEdge<=0) throw new IOException("屏幕尺寸无效："+screenWidth+" × "+screenHeight);
        if (pixels < minimumLongEdge(longEdge) || pixels > longEdge) {
            throw new IOException("长边须在 "+minimumLongEdge(longEdge)+" 至 " + longEdge + " 之间");
        }
        // Store the requested aspect-correct rectangle directly. A fixed 2:1
        // rectangle undershoots the requested long edge on ultrawide phones
        // and needlessly expands the encoded width on square/foldable panels.
        int targetHeight = (int)Math.round((double)pixels*shortEdge/longEdge);
        return new ResolutionSpec(pixels,Math.max(1,targetHeight));
    }
    public static int minimumLongEdge(int longEdge) {return Math.min(720,longEdge);}
    public static int[] presetLongEdges(int longEdge) {
        double[] ratios={.421875,.5625,2.0/3,.75,5.0/6,1};
        java.util.Set<Integer> values=new java.util.LinkedHashSet<>();
        for(double ratio:ratios) values.add(Math.max(minimumLongEdge(longEdge),(int)Math.round(longEdge*ratio)));
        int[] result=new int[values.size()];int i=0;
        for(int value:values) result[i++]=value;
        return result;
    }
    public double scale(int screenWidth, int screenHeight) {
        return Math.max(0.1, Math.min(1.0, Math.min((double) width / screenWidth, (double) height / screenHeight)));
    }
    public String predictedLabel(int screenWidth, int screenHeight) {
        double scale = scale(screenWidth, screenHeight);
        int w = (int) Math.round(screenWidth * scale), h = (int) Math.round(screenHeight * scale);
        return w + " × " + h;
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ResolutionSpec)) return false;
        ResolutionSpec r = (ResolutionSpec) other;
        return width == r.width && height == r.height;
    }
    @Override public int hashCode() { return 31 * width + height; }
}
