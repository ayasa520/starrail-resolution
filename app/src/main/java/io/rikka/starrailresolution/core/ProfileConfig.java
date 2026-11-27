package io.rikka.starrailresolution.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Schema decoder and length-preserving editor; pack authenticity is checked separately. */
public final class ProfileConfig {
    public static final String OFFICIAL_SHA256 = "c9fa969e1949dcbec7801f0f48e6dfa35c142fdab25db8a53bdfaf387a7abc9e";
    private static final String LEGACY_PROFILE = "Adreno VeryHigh";
    private static final int[][] ORIGINAL = {{1080,540},{1280,640},{1440,720},{1680,840},{2160,1080}};
    private final byte[] bytes;
    private final Map<String, Span> spans = new HashMap<>();
    private final Map<String, ResolutionTable> tables = new LinkedHashMap<>();
    private final Map<String, Object> devices;
    private int position;

    private static final class Span {
        final int offset, size, value;
        Span(int offset, int size, int value) { this.offset=offset; this.size=size; this.value=value; }
    }

    /** An explicitly serialized table; profiles without one inherit the default. */
    public static final class ResolutionTable {
        public final String name;
        private final List<ResolutionSpec> entries;
        private ResolutionTable(String name,List<ResolutionSpec> entries) {
            this.name=name;this.entries=Collections.unmodifiableList(new ArrayList<>(entries));
        }
        public int size() {return entries.size();}
        public ResolutionSpec get(int index) {return entries.get(index);}
        public ResolutionSpec highest() {return entries.get(entries.size()-1);}
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

    @SuppressWarnings("unchecked")
    public ProfileConfig(byte[] input) throws IOException {
        if (input.length < 100 || input.length > 1000000) throw new IOException("设备配置大小异常");
        bytes = input.clone();
        Map<String,Object> root=(Map<String,Object>)read("Root", "", 0);
        if (position != bytes.length) throw new IOException("设备配置尾部存在未识别数据");
        devices=(Map<String,Object>)root.get("deviceSettings");
        if(devices==null) throw new IOException("设备配置缺少 deviceSettings");
        // Discover serialized arrays from the decoded resource rather than
        // inferring a GPU family from a hard-coded path or a five-row layout.
        // Updating every explicit table also covers profiles that inherit the
        // Default table without creating new fields or changing GPU matching.
        for(Map.Entry<String,Object> device:devices.entrySet()) {
            Map<String,Object> fields=(Map<String,Object>)device.getValue();
            List<Object> values=(List<Object>)fields.get("renderResolutions");
            if(values==null) continue;
            if(values.isEmpty() || values.size()>32) throw new IOException("分辨率表档位数量异常："+device.getKey());
            List<ResolutionSpec> entries=new ArrayList<>();
            for(int i=0;i<values.size();i++) {
                Span width=span(device.getKey(),i,"width"),height=span(device.getKey(),i,"height");
                if(width.value<1 || height.value<1) throw new IOException("分辨率字段无效："+device.getKey()+" / "+i);
                entries.add(new ResolutionSpec(width.value,height.value));
            }
            tables.put(device.getKey(),new ResolutionTable(device.getKey(),entries));
        }
        if(tables.isEmpty()) throw new IOException("设备配置没有可编辑的分辨率表");
    }

    public List<ResolutionTable> resolutionTables() {return Collections.unmodifiableList(new ArrayList<>(tables.values()));}

    public ResolutionTable table(String name) throws IOException {
        ResolutionTable table=tables.get(name);
        if(table==null) throw new IOException("未找到分辨率表："+name);
        return table;
    }

    public ResolutionSpec resolution(String name,int grade) throws IOException {
        ResolutionTable table=table(name);
        if(grade<0 || grade>=table.size()) throw new IOException("无效分辨率档位："+name+" / "+grade);
        return table.get(grade);
    }

    @SuppressWarnings("unchecked")
    public String previewTableName(String gpu) throws IOException {
        // This is a display hint, never a write selector. All tables are
        // patched, so unknown/overlapping hardware rules cannot leave another
        // GPU family unmodified. Only unambiguous GPU-only rules are used.
        List<String> matches=new ArrayList<>();
        for(String name:tables.keySet()) {
            Map<String,Object> fields=(Map<String,Object>)devices.get(name);
            List<Map<String,Object>> rules=(List<Map<String,Object>>)fields.get("requirements");
            if(rules==null || rules.isEmpty()) continue;
            boolean matched=true;
            for(Map<String,Object> rule:rules) {
                if(!"GraphicsDeviceName".equals(rule.get("Info"))) {matched=false;break;}
                boolean any=false;
                List<String> patterns=(List<String>)rule.get("Values");
                if(patterns==null) {matched=false;break;}
                for(String pattern:patterns) {
                    try {any|=java.util.regex.Pattern.compile(pattern).matcher(gpu).find();}
                    catch(java.util.regex.PatternSyntaxException invalid) {matched=false;break;}
                }
                matched&=any;
            }
            if(matched) matches.add(name);
        }
        if(matches.size()==1) return matches.get(0);
        if(tables.containsKey("Default")) return "Default";
        return tables.keySet().iterator().next();
    }

    public byte[] changeHighest(ResolutionSpec spec) throws IOException {
        byte[] result = bytes.clone();
        for(ResolutionTable table:tables.values()) replaceHighest(result,table,spec);
        // Re-parse the complete edited entry so a schema or varint-layout
        // change cannot turn a resolution edit into an unrelated field edit.
        for(ResolutionTable table:new ProfileConfig(result).tables.values()) {
            if(!table.highest().equals(spec)) throw new IOException("修改后的配置校验失败："+table.name);
        }
        return result;
    }

    /** Used for exact legacy-patch verification; production updates every table. */
    public byte[] changeHighest(String name,ResolutionSpec spec) throws IOException {
        byte[] result=bytes.clone();replaceHighest(result,table(name),spec);new ProfileConfig(result);return result;
    }

    private void replaceHighest(byte[] result,ResolutionTable table,ResolutionSpec spec) throws IOException {
        replace(result,span(table.name,table.size()-1,"width"),spec.width);
        replace(result,span(table.name,table.size()-1,"height"),spec.height);
    }

    public byte[] restoreResolutions(ProfileConfig official) throws IOException {
        byte[] result = bytes.clone();
        if(!tables.keySet().equals(official.tables.keySet())) throw new IOException("原始配置的分辨率表集合不符");
        // Normalize only fields owned by this helper. Lower grades, hardware
        // rules, and every unrelated byte must still pass the full-pack hash.
        for(ResolutionTable table:tables.values()) {
            ResolutionTable original=official.table(table.name);
            if(table.size()!=original.size()) throw new IOException("原始配置档位数量不符："+table.name);
            replaceHighest(result,table,original.highest());
        }
        return result;
    }

    /** Recover only the exact legacy entry used before per-resource baselines existed. */
    public byte[] legacyOriginalEntry() throws IOException {
        byte[] result = bytes.clone();
        for (int i=0;i<5;i++) {
            replace(result, span(LEGACY_PROFILE,i,"width"), ORIGINAL[i][0]);
            replace(result, span(LEGACY_PROFILE,i,"height"), ORIGINAL[i][1]);
        }
        // This fingerprint is a migration path for old ADB/helper edits, not
        // an allowlist for new game releases. New releases use their own
        // manifest-authenticated original entry in VerifiedProfile.
        if (!Hashing.sha256(result).equals(OFFICIAL_SHA256)) {
            throw new IOException("当前资源不是可恢复原始配置的旧版资源");
        }
        return result;
    }

    private Span span(String name,int index, String field) throws IOException {
        Span s = spans.get("/deviceSettings/"+name+"/renderResolutions/" + index + "/" + field);
        if (s == null) throw new IOException("分辨率字段缺失："+name+" / "+index+" / "+field);
        return s;
    }

    private static void replace(byte[] target, Span span, int value) throws IOException {
        if (value < 1) throw new IOException("目标分辨率必须为正数");
        long unsigned = (long)value << 1;
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        do {
            int next = (int)(unsigned & 127); unsigned >>>= 7;
            encoded.write(unsigned == 0 ? next : next | 128);
        } while (unsigned != 0);
        if (encoded.size() != span.size) throw new IOException("目标值 "+value+" 需要 "+encoded.size()
            +" 字节，原字段为 "+span.size+" 字节，偏移 "+span.offset+"；未写入");
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
                if((mask >>> fields.length)!=0) throw new IOException("配置含未识别字段："+type+" "+path
                    +"，偏移 "+position+"，字段掩码 0x"+Long.toHexString(mask));
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
