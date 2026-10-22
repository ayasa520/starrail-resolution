package io.rikka.starrailresolution;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import io.rikka.starrailresolution.core.ResolutionSpec;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG=0xff10141d,CARD=0xff1b2230,TEXT=0xffe6edf9,MUTED=0xffa7b4ca,ACCENT=0xff8cbaff;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayList<Button> actions=new ArrayList<>();
    private final Map<Button,Integer> presetWidths=new LinkedHashMap<>();
    private static final int MIN_LONG_EDGE=720;
    private TextView state,current,details,selectionDimensions,rangeMinimum,rangeMaximum,preview,notice,diagnostics;
    private SeekBar resolutionSlider;
    private Button apply,undo,official,refresh;
    private ProgressBar progress;
    private RootGateway gateway;
    private JSONObject latest;
    private int screenWidth=3840,screenHeight=2560;
    private boolean busy,ready;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);gateway=new RootGateway(getApplicationContext());buildUi();
        int selected=2560;
        try {selected=Integer.parseInt(getPreferences(MODE_PRIVATE).getString("selectedWidth","2560"));}
        catch(NumberFormatException ignored) {}
        if(saved!=null) selected=saved.getInt("selectedWidth",selected);
        resolutionSlider.setProgress(Math.max(MIN_LONG_EDGE,Math.min(screenWidth,selected)));
        updatePreview();
        run("status",null);
    }

    private void buildUi() {
        FrameLayout outer=new FrameLayout(this);outer.setBackgroundColor(BG);
        outer.setOnApplyWindowInsetsListener((view,insets)-> {
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);outer.addView(scroll);
        FrameLayout centering=new FrameLayout(this);scroll.addView(centering);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(26),dp(20),dp(30));
        FrameLayout.LayoutParams centered=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        centering.addView(body,centered);
        outer.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)-> {
            int available=outer.getWidth()-outer.getPaddingLeft()-outer.getPaddingRight();
            int wanted=Math.min(available,dp(760));
            if(body.getLayoutParams().width!=wanted) { body.getLayoutParams().width=wanted;body.requestLayout(); }
        });
        add(body,label("星铁分辨率助手",27,TEXT,true),0);
        add(body,label("调整 3D 清晰度，保留其他画质设置",14,MUTED,false),8);
        state=label("正在检测 · 首次使用请授予 Root 权限",13,ACCENT,false);add(body,state,16);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));add(body,progress,12);

        LinearLayout currentCard=card(body,20);
        add(currentCard,label("当前最高分辨率档",13,MUTED,false),0);
        current=label("读取中",36,TEXT,true);add(currentCard,current,10);
        details=label("目标尺寸由配置和代码公式推算，非运行时实测。",13,MUTED,false);add(currentCard,details,8);

        LinearLayout selection=card(body,16);add(selection,label("拖动调整 3D 分辨率",19,TEXT,true),0);
        selectionDimensions=label("",34,TEXT,true);add(selection,selectionDimensions,14);
        add(selection,label("长边 × 短边 · 像素",13,MUTED,false),4);
        resolutionSlider=new SeekBar(this);
        // Android clamps a new minimum to the widget's existing maximum.
        // Set the real maximum first so the default maximum of 100 cannot
        // silently lower the intended 720-pixel bound.
        resolutionSlider.setMax(screenWidth);resolutionSlider.setMin(MIN_LONG_EDGE);
        resolutionSlider.setKeyProgressIncrement(1);resolutionSlider.setMinHeight(dp(48));
        resolutionSlider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        resolutionSlider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        resolutionSlider.setProgressBackgroundTintList(ColorStateList.valueOf(0xff3a475d));
        resolutionSlider.setContentDescription("调整 3D 分辨率");add(selection,resolutionSlider,12);
        LinearLayout range=new LinearLayout(this);range.setOrientation(LinearLayout.HORIZONTAL);
        rangeMinimum=label("",12,MUTED,false);rangeMaximum=label("",12,MUTED,false);rangeMaximum.setGravity(Gravity.END);
        range.addView(rangeMinimum,new LinearLayout.LayoutParams(0,-2,1));
        range.addView(rangeMaximum,new LinearLayout.LayoutParams(0,-2,1));add(selection,range,0);
        addPresets(selection);
        preview=label("",14,ACCENT,false);add(selection,preview,10);
        // Slider gestures stage a target locally and never write game files
        // or restart the game. Keep the selected long edge as the displayed
        // intent; derive its short edge with the same aspect-ratio conversion
        // used by the file worker. Presets stage the same value through this
        // slider; free dragging never snaps to one of those shortcuts.
        resolutionSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar slider,int value,boolean fromUser) {updatePreview();}
            public void onStartTrackingTouch(SeekBar slider) {slider.getParent().requestDisallowInterceptTouchEvent(true);}
            public void onStopTrackingTouch(SeekBar slider) {
                slider.getParent().requestDisallowInterceptTouchEvent(false);
                rememberSelection();
            }
        });
        apply=button("应用并全屏重启游戏",true);apply.setOnClickListener(v-> {
            try {
                int value=resolutionSlider.getProgress();
                ResolutionSpec.forLongEdge(value,screenWidth,screenHeight);
                rememberSelection();
                run("apply",value);
            } catch(Exception e) { showMessage(e.getMessage(),false); }
        });add(body,apply,18);
        notice=label("",14,MUTED,false);notice.setTextIsSelectable(true);add(body,notice,12);
        LinearLayout recovery=card(body,16);add(recovery,label("备份与恢复",19,TEXT,true),0);
        undo=button("撤销上次修改",false);undo.setOnClickListener(v->run("undo",null));add(recovery,undo,12);
        official=button("恢复官方分辨率档位",false);official.setOnClickListener(v->run("official",null));add(recovery,official,8);
        add(recovery,label("每次写入和撤销都会留存回退记录。备份保存在 Download/StarRailResolution。",12,MUTED,false),10);
        refresh=button("重新检测",false);refresh.setOnClickListener(v->run("status",null));add(body,refresh,16);
        add(body,label("仅修改游戏内「分辨率：非常高」对应的 3D 目标，请在游戏中选择这一档。应用会退出并重启游戏。\n\n首版适配：国际服 4.6.0 · Adreno 830 · 3840×2560。需要 Root，无网络权限。",12,MUTED,false),16);
        Button log=button("查看 / 复制诊断",false);add(body,log,14);
        diagnostics=label("",12,MUTED,false);diagnostics.setTextIsSelectable(true);diagnostics.setVisibility(View.GONE);add(body,diagnostics,8);
        log.setOnClickListener(v-> {
            diagnostics.setVisibility(diagnostics.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("星铁分辨率助手诊断",diagnostics.getText()));
            Toast.makeText(this,"诊断已复制",Toast.LENGTH_SHORT).show();
        });
        setContentView(outer);outer.requestApplyInsets();setBusy(false);
    }

    private void run(String action,Integer width) {
        setBusy(true);state.setText(action.equals("status")?"正在读取配置与校验资源…":"正在备份、校验并处理配置…");
        showMessage(action.equals("status")?"首次使用请在 Root 管理器提示中授予权限。":"请稍候，完成后会全屏启动游戏。",true);
        worker.submit(()-> {
            JSONObject result;
            try { result=gateway.execute(action,width); }
            catch(Exception e) {
                result=new JSONObject();try { result.put("ok",false).put("error",e.getMessage()); } catch(JSONException ignored) {}
            }
            JSONObject output=result;
            runOnUiThread(()-> {
                if(isDestroyed()) return;
                setBusy(false);
                try { diagnostics.setText(output.toString(2)); } catch(JSONException ignored) {diagnostics.setText(output.toString());}
                if(!output.optBoolean("ok")) {
                    ready=false;state.setText("未完成 · 详见下方诊断");showMessage(output.optString("error","操作失败"),false);updateEnabled();return;
                }
                latest=output;ready=true;
                screenWidth=output.optInt("screenWidth",3840);screenHeight=output.optInt("screenHeight",2560);
                current.setText("≈ "+output.optString("predicted"));
                String info="国际服 "+output.optString("version")+" · "+output.optString("gpu")+"\n配置推算，非运行时实测";
                if(output.optInt("grade",-1)!=5) info+="\n请将游戏内分辨率选择为「非常高」。";
                details.setText(info);state.setText("已连接 · 资源校验通过");
                showMessage(output.optString("message","配置已读取。选择目标后应用即可。"),true);
                resolutionSlider.setMax(screenWidth);resolutionSlider.setKeyProgressIncrement(1);updatePreview();updateEnabled();
            });
        });
    }

    private void updatePreview() {
        if(resolutionSlider==null || preview==null) return;
        try {
            int pixels=resolutionSlider.getProgress();
            ResolutionSpec spec=ResolutionSpec.forLongEdge(pixels,screenWidth,screenHeight);
            String dimensions=pixels+" × "+spec.height;
            selectionDimensions.setText(dimensions);resolutionSlider.setStateDescription(dimensions+" 像素");
            for(Map.Entry<Button,Integer> preset:presetWidths.entrySet()) {
                boolean selected=preset.getValue()==pixels;
                if(preset.getKey().isSelected()!=selected) {
                    preset.getKey().setSelected(selected);styleButton(preset.getKey(),selected);
                }
            }
            rangeMinimum.setText(MIN_LONG_EDGE+" × "+ResolutionSpec.forLongEdge(MIN_LONG_EDGE,screenWidth,screenHeight).height);
            rangeMaximum.setText(screenWidth+" × "+screenHeight);
            String text="拖动后点「应用」生效，按屏幕比例保持画面形状。";
            if(latest!=null) {
                ResolutionSpec old=new ResolutionSpec(latest.optInt("targetWidth"),latest.optInt("targetHeight"));
                double ratio=Math.pow(spec.scale(screenWidth,screenHeight)/old.scale(screenWidth,screenHeight),2);
                text+="\n像素量约为当前的 "+Math.round(ratio*100)+"%（不等于帧率变化）";
            }
            preview.setText(text);preview.setTextColor(ACCENT);
        } catch(Exception e) {preview.setText(e.getMessage());preview.setTextColor(MUTED);}
    }
    private void addPresets(LinearLayout selection) {
        add(selection,label("常用档位",13,MUTED,false),14);
        GridLayout grid=new GridLayout(this);grid.setColumnCount(3);add(selection,grid,4);
        int[] widths={1620,2160,2560,2880,3200,3840};
        for(int i=0;i<widths.length;i++) {
            int pixels=widths[i];
            int shortEdge=(int)Math.round((double)pixels*screenHeight/screenWidth);
            Button preset=button(pixels+" × "+shortEdge,false);preset.setTextSize(13);
            presetWidths.put(preset,pixels);
            preset.setOnClickListener(v-> {
                resolutionSlider.setProgress(pixels);rememberSelection();updatePreview();
            });
            GridLayout.LayoutParams params=new GridLayout.LayoutParams();params.width=0;
            params.columnSpec=GridLayout.spec(i%3,1f);params.rowSpec=GridLayout.spec(i/3);
            params.setMargins(dp(i%3==0?0:4),dp(4),dp(i%3==2?0:4),dp(4));grid.addView(preset,params);
        }
    }
    private void rememberSelection() {
        getPreferences(MODE_PRIVATE).edit().putString("selectedWidth",Integer.toString(resolutionSlider.getProgress())).apply();
    }
    private void setBusy(boolean value) {
        busy=value;if(progress!=null) progress.setVisibility(value?View.VISIBLE:View.GONE);updateEnabled();
    }
    private void updateEnabled() {
        if(apply==null) return;
        for(Button button:actions) button.setEnabled(!busy);
        apply.setEnabled(!busy&&ready);official.setEnabled(!busy&&ready);
        undo.setEnabled(!busy&&ready&&latest!=null&&latest.optBoolean("canUndo"));
        refresh.setEnabled(!busy);resolutionSlider.setEnabled(!busy);
    }
    private void showMessage(String text,boolean good) {
        notice.setText(text==null?"操作失败":text);notice.setTextColor(good?MUTED:0xffffbdab);
    }
    private LinearLayout card(LinearLayout parent,int margin) {
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20),dp(20),dp(20),dp(20));card.setBackground(shape(CARD,18));add(parent,card,margin);return card;
    }
    private TextView label(String text,int size,int color,boolean medium) {
        TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(color);
        view.setLineSpacing(dp(3),1);view.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return view;
    }
    private Button button(String text,boolean primary) {
        Button button=new Button(this);button.setText(text);button.setAllCaps(false);button.setTextSize(15);
        button.setMinHeight(dp(50));button.setPadding(dp(14),dp(8),dp(14),dp(8));styleButton(button,primary);
        actions.add(button);return button;
    }
    private void styleButton(Button button,boolean highlighted) {
        button.setTextColor(highlighted?0xff0e243f:TEXT);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x228cbaff),shape(highlighted?ACCENT:0xff293449,12),null));
    }
    private GradientDrawable shape(int color,int radius) {
        GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;
    }
    private void add(LinearLayout parent,View child,int top) {
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(top);parent.addView(child,params);
    }
    private int dp(int value) {return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("selectedWidth",resolutionSlider.getProgress());super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() {super.onDestroy();worker.shutdown();}
}
