package io.rikka.starrailresolution.core;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Reads the version-4 DesignData index; no resource offsets are hard-coded. */
public final class ResourceManifest {
    public static final long DEVICE_CONFIG_HASH = 0x98a6ccac21eabbe7L;

    public static final class Entry {
        public final String packMd5;
        public final long packSize, offset;
        public final int size;
        public Entry(String md5, long packSize, long offset, int size) {
            this.packMd5 = md5; this.packSize = packSize; this.offset = offset; this.size = size;
        }
    }

    public static Entry locate(byte[] manifest) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(manifest));
        if (in.readInt() != 255 || in.readInt() != 4) throw new IOException("不支持的资源清单格式");
        int packs = in.readInt(), total = in.readInt();
        if (packs < 1 || packs > 10000 || total < 1 || total > 5000000) throw new IOException("资源清单计数异常");
        Entry found = null;
        long seen = 0;
        for (int pack = 0; pack < packs; pack++) {
            in.readLong();
            byte[] md5 = new byte[16]; in.readFully(md5);
            String md5String = Hashing.hex(md5);
            long packSize = in.readLong(); int count = in.readInt();
            if (packSize < 1 || count < 0 || count > total - seen) throw new IOException("资源包信息异常");
            for (int i = 0; i < count; i++) {
                long hash = in.readLong();
                long size = Integer.toUnsignedLong(in.readInt());
                long offset = Integer.toUnsignedLong(in.readInt());
                if (offset > packSize || size > packSize - offset) throw new IOException("资源条目超出包边界");
                if (hash == DEVICE_CONFIG_HASH) {
                    if (found != null || size < 100 || size > 1000000) throw new IOException("设备配置定位不唯一");
                    found = new Entry(md5String, packSize, offset, (int) size);
                }
            }
            seen += count;
            int languageLength = in.readUnsignedShort();
            byte[] language = new byte[languageLength]; in.readFully(language);
            new String(language, StandardCharsets.UTF_8);
            if (in.readUnsignedByte() != 128) throw new IOException("不支持的资源封装标志");
        }
        if (seen != total || in.available() != 0 || found == null) throw new IOException("资源清单不完整或未找到设备配置");
        return found;
    }

    private ResourceManifest() {}
}
