package io.rikka.starrailresolution.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Exact schema decoder and length-preserving editor for the verified config. */
public final class ProfileConfig {
    public static final String OFFICIAL_SHA256 = "c9fa969e1949dcbec7801f0f48e6dfa35c142fdab25db8a53bdfaf387a7abc9e";
    private static final String TABLE = "/deviceSettings/Adreno VeryHigh/renderResolutions/";
    private static final int[][] ORIGINAL = {{1080,540},{1280,640},{1440,720},{1680,840},{2160,1080}};
    private final byte[] bytes;
    private final Map<String, Span> spans = new HashMap<>();
    private int position;

    private static final class Span {
        final int offset, size, value;
        Span(int offset, int size, int value) { this.offset=offset; this.size=size; this.value=value; }
    }

    private static final Map<String,String[]> SCHEMAS = new HashMap<>();
    static {
        SCHEMAS.put("Resolution", new String[]{"width:int","height:int"});
        SCHEMAS.put("Requirements", new String[]{"Info:str","Values:[]str"});
        SCHEMAS.put("Device", new String[]{"requirements:[]Requirements","renderResolutions:[]Resolution",
            "defaultVolatileGrade:int","Support120FPS:bool","enterBattleSwitchHLODAdvScene:bool",
            "PersistentLayerHighestLodLevel:int","DynamicLoadBattleArea:bool","DynamicUnloadBattleArea:bool",
            "EnableStoryStreamingSource:bool","TextureStreamingMemoryBudget:int","SupportMetalFX:bool"});
        SCHEMAS.put("Model", new String[]{"DeviceModel:str","ApiLevel:int"});
        SCHEMAS.put("Graphic", new String[]{"GraphicDeviceName:str","ApiLevel:int"});
        SCHEMAS.put("CpuGpu", new String[]{"GraphicDeviceName:str","DeviceModel:str","GraphicsDeviceType:int","DisableType:int"});
        SCHEMAS.put("ForceGles", new String[]{"DeviceModelList:[]Model","GraphicDeviceList:[]Graphic"});
        SCHEMAS.put("ForceVulkan", new String[]{"ForceGlesDeviceModelList:[]Model","DeviceModelList:[]Model","GraphicDeviceList:[]Graphic"});
        SCHEMAS.put("TempFix", new String[]{"TempFixDeviceModelList:[]CpuGpu"});
        SCHEMAS.put("Root", new String[]{"deviceSettings:{}Device","forceGlesDevices:ForceGles",
            "forceVulkanDevices:ForceVulkan","tempFixDevice:TempFix","ForceGraphicsJob:int","BackbufferColorSpace:int"});
    }

    public ProfileConfig(byte[] input) throws IOException {
        if (input.length < 100 || input.length > 1000000) throw new IOException("设备配置大小异常");
        bytes = input.clone();
        read("Root", "", 0);
        if (position != bytes.length) throw new IOException("设备配置尾部存在未识别数据");
        for (int i=0;i<5;i++) { span(i,"width"); span(i,"height"); }
        // Normalize only the known resolution fields before checking the
        // original entry fingerprint. Other GPU profiles and every unrelated
        // byte must still match the exact config investigated on this tablet.
        if (!Hashing.sha256(originalEntry()).equals(OFFICIAL_SHA256)) {
            throw new IOException("当前设备配置与已验证的 4.6.0 资源不匹配，未修改");
        }
    }

    public ResolutionSpec resolution(int grade) throws IOException {
        if (grade < 0 || grade > 4) throw new IOException("无效分辨率档位");
        return new ResolutionSpec(span(grade,"width").value, span(grade,"height").value);
    }

    public byte[] changeHighest(ResolutionSpec spec) throws IOException {
        byte[] result = bytes.clone();
        replace(result, span(4,"width"), spec.width);
        replace(result, span(4,"height"), spec.height);
        // Re-parse the complete edited entry. It must retain the verified
        // canonical fingerprint and the requested dimensions, not merely a
        // matching byte pattern that could belong to another GPU family.
        if (!new ProfileConfig(result).resolution(4).equals(spec)) throw new IOException("修改后的配置校验失败");
        return result;
    }

    public byte[] originalEntry() throws IOException {
        byte[] result = bytes.clone();
        for (int i=0;i<5;i++) {
            replace(result, span(i,"width"), ORIGINAL[i][0]);
            replace(result, span(i,"height"), ORIGINAL[i][1]);
        }
        return result;
    }

    private Span span(int index, String field) throws IOException {
        Span s = spans.get(TABLE + index + "/" + field);
        if (s == null) throw new IOException("未找到 Adreno 最高设备配置");
        return s;
    }

    private static void replace(byte[] target, Span span, int value) throws IOException {
        if (value < 1 || value > 8191) throw new IOException("目标分辨率超出可保持原布局的范围");
        int unsigned = value << 1;
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        do {
            int next = unsigned & 127; unsigned >>>= 7;
            encoded.write(unsigned == 0 ? next : next | 128);
        } while (unsigned != 0);
        if (encoded.size() != span.size) throw new IOException("目标值会改变资源长度，已停止");
        System.arraycopy(encoded.toByteArray(),0,target,span.offset,span.size);
    }

    private Object read(String type, String path, int depth) throws IOException {
        if (depth > 32) throw new IOException("配置嵌套过深");
        if (type.startsWith("[]")) {
            int count = integer();
            if (count < 0 || count > 100000) throw new IOException("配置数组计数异常");
            List<Object> result = new ArrayList<>();
            for(int i=0;i<count;i++) result.add(read(type.substring(2),path+"/"+i,depth+1));
            return result;
        }
        if (type.startsWith("{}")) {
            int count = integer();
            if (count < 0 || count > 10000) throw new IOException("配置字典计数异常");
            Map<String,Object> result = new LinkedHashMap<>();
            for(int i=0;i<count;i++) {
                String key = string();
                if (result.containsKey(key)) throw new IOException("配置字典包含重复键");
                result.put(key,read(type.substring(2),path+"/"+key,depth+1));
            }
            return result;
        }
        switch(type) {
            case "int": {
                int start=position, value=integer();
                spans.put(path,new Span(start,position-start,value));
                return value;
            }
            case "str": return string();
            case "bool": {
                int value=byteValue();
                if(value!=0 && value!=1) throw new IOException("布尔字段无效");
                return value==1;
            }
            default: {
                String[] fields=SCHEMAS.get(type);
                if(fields==null) throw new IOException("未知配置类型");
                long mask=unsigned();
                if((mask >>> fields.length)!=0) throw new IOException("配置含新字段，未修改");
                Map<String,Object> result=new LinkedHashMap<>();
                for(int i=0;i<fields.length;i++) if((mask & (1L<<i))!=0) {
                    String[] field=fields[i].split(":",2);
                    result.put(field[0],read(field[1],path+"/"+field[0],depth+1));
                }
                return result;
            }
        }
    }
    private int byteValue() throws IOException {
        if(position>=bytes.length) throw new EOFException("配置意外结束");
        return bytes[position++] & 255;
    }
    private long unsigned() throws IOException {
        long value=0;
        for(int shift=0;shift<=28;shift+=7) {
            int b=byteValue();value|=(long)(b&127)<<shift;
            if((b&128)==0) {
                if(value>0xffffffffL) throw new IOException("整数超出范围");
                return value;
            }
        }
        throw new IOException("配置变长整数无效");
    }
    private int integer() throws IOException {
        long value=unsigned(); return (int)((value>>>1)^-(value&1));
    }
    private String string() throws IOException {
        long count=unsigned();
        if(count>bytes.length-position) throw new IOException("字符串超出配置边界");
        String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes,position,(int)count)).toString();
        position+=(int)count;return value;
    }
}
