package io.rikka.starrailresolution.core;

import java.io.IOException;

public final class ResolutionSpec {
    public final int width, height;
    public ResolutionSpec(int width, int height) { this.width = width; this.height = height; }

    public static ResolutionSpec forLongEdge(int pixels, int screenWidth, int screenHeight) throws IOException {
        if (screenWidth < screenHeight || (double) screenWidth / screenHeight < 1.4) {
            throw new IOException("当前屏幕比例不在已验证范围");
        }
        if (pixels < 720 || pixels > screenWidth) throw new IOException("长边须在 720 至 " + screenWidth + " 之间");
        int targetHeight = (int) Math.round((double) pixels * screenHeight / screenWidth);
        return new ResolutionSpec(targetHeight * 2, targetHeight);
    }
    public double scale(int screenWidth, int screenHeight) {
        return Math.max(0.1, Math.min(1.0, Math.min((double) width / screenWidth, (double) height / screenHeight)));
    }
    public String predictedLabel(int screenWidth, int screenHeight) {
        double scale = scale(screenWidth, screenHeight);
        int w = (int) Math.round(screenWidth * scale), h = (int) Math.round(screenHeight * scale);
        // These familiar preset names are approximate targets, not measured
        // render-target dimensions. The engine can align them by a pixel.
        int[] presets = {1620, 2160, 2560, 2880, 3200, screenWidth};
        for (int preset : presets) if (Math.abs(w - preset) <= 1) { w = preset; break; }
        return w + " × " + h;
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ResolutionSpec)) return false;
        ResolutionSpec r = (ResolutionSpec) other;
        return width == r.width && height == r.height;
    }
    @Override public int hashCode() { return 31 * width + height; }
}
