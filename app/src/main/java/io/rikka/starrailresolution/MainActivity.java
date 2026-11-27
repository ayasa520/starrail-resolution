package io.rikka.starrailresolution;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import io.rikka.starrailresolution.core.ResolutionSpec;
import io.rikka.starrailresolution.core.GameTarget;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int BG=0xff10141d,CARD=0xff1b2230,TEXT=0xffe6edf9,MUTED=0xffa7b4ca,ACCENT=0xff8cbaff;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayList<Button> actions=new ArrayList<>();
    private final Map<Button,Integer> presetWidths=new LinkedHashMap<>();
    private static final int SHIZUKU_PERMISSION_REQUEST=1001;
    private TextView state,current,details,selectionDimensions,rangeMinimum,rangeMaximum,preview,notice,diagnostics;
    private SeekBar resolutionSlider;
    private GridLayout presetGrid;
    private Button apply,undo,official,refresh;
    private ProgressBar progress;
    private PrivilegedGateway gateway;
    private RadioGroup authorization;
    private RadioButton rootMethod,shizukuMethod;
    private RadioGroup gameSelection;
    private GameTarget selectedGame=GameTarget.INTERNATIONAL;
    private TextView authorizationStatus;
    private boolean useShizuku,permissionPending,busy,ready;
    private String pendingAction;
    private Integer pendingWidth;
    private final Shizuku.OnRequestPermissionResultListener permissionListener=(code,result)->runOnUiThread(()-> {
        if(code!=SHIZUKU_PERMISSION_REQUEST || !permissionPending || isDestroyed()) return;
        permissionPending=false;setBusy(false);
        if(result==PackageManager.PERMISSION_GRANTED) executeAction(pendingAction,pendingWidth);
        else authorizationFailed("Shizuku 授权被拒绝，可在 Shizuku 的授权应用管理中允许后重新检测");
    });
    private final Shizuku.OnBinderReceivedListener binderReceived=()->runOnUiThread(this::updateAuthorizationStatus);
    private final Shizuku.OnBinderDeadListener binderDead=()->runOnUiThread(()-> {
        if(isDestroyed()) return;
        updateAuthorizationStatus();
        if(useShizuku && permissionPending) {
            permissionPending=false;setBusy(false);
            authorizationFailed("Shizuku 在授权期间停止，请重新启动后检测");
        } else if(useShizuku && !busy) authorizationFailed("Shizuku 已停止，请重新启动后检测");
    });
    private JSONObject latest;
    private int screenWidth,screenHeight;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Point size=new Point();getWindowManager().getDefaultDisplay().getRealSize(size);
        screenWidth=Math.max(size.x,size.y);screenHeight=Math.min(size.x,size.y);
        try {selectedGame=GameTarget.forPackage(getPreferences(MODE_PRIVATE).getString("gamePackage",GameTarget.INTERNATIONAL.packageName));}
        catch(java.io.IOException invalid) {android.util.Log.w("MainActivity","Invalid saved game selection",invalid);}
        useShizuku=getPreferences(MODE_PRIVATE).getString("authorization","root").equals("shizuku");
        selectGateway();buildUi();
        Shizuku.addRequestPermissionResultListener(permissionListener);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        int selected=Math.max(ResolutionSpec.minimumLongEdge(screenWidth),(int)Math.round(screenWidth*2.0/3));
        try {selected=Integer.parseInt(getPreferences(MODE_PRIVATE).getString("selectedWidth",Integer.toString(selected)));}
        catch(NumberFormatException ignored) {}
        if(saved!=null) selected=saved.getInt("selectedWidth",selected);
        resolutionSlider.setProgress(Math.max(ResolutionSpec.minimumLongEdge(screenWidth),Math.min(screenWidth,selected)));
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
        addGameSelection(body);
        addAuthorization(body);
        state=label("正在检测授权与资源",13,ACCENT,false);add(body,state,16);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));add(body,progress,12);

        LinearLayout currentCard=card(body,20);
        add(currentCard,label("当前最高分辨率档",13,MUTED,false),0);
        current=label("读取中",36,TEXT,true);add(currentCard,current,10);
        details=label("检测后显示当前档位与设备信息。",13,MUTED,false);add(currentCard,details,8);

        LinearLayout selection=card(body,16);add(selection,label("拖动调整 3D 分辨率",19,TEXT,true),0);
        selectionDimensions=label("",34,TEXT,true);add(selection,selectionDimensions,14);
        add(selection,label("长边 × 短边 · 像素",13,MUTED,false),4);
        resolutionSlider=new SeekBar(this);
        // Android clamps a new minimum to the widget's existing maximum.
        // Set the real maximum first so the default maximum of 100 cannot
        // silently lower the intended 720-pixel bound.
        resolutionSlider.setMax(screenWidth);resolutionSlider.setMin(ResolutionSpec.minimumLongEdge(screenWidth));
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
        add(body,label("修改各设备分辨率表的最高档，请在游戏内选择最高可用的分辨率档位。应用会退出并重启所选游戏。\n\n国服与国际服分别检测和备份，按实际资源结构判断兼容性。目标尺寸按当前屏幕比例计算，支持 Root 或 Shizuku 授权。",12,MUTED,false),16);
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
        if(useShizuku) {
            try {
                if(!Shizuku.pingBinder()) {authorizationFailed("请先安装并启动 Shizuku，再点「授权并检测」");return;}
                if(Shizuku.isPreV11()) {authorizationFailed("Shizuku 版本过旧，请更新后重新检测");return;}
                if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) {
                    if(Shizuku.shouldShowRequestPermissionRationale()) {
                        authorizationFailed("请在 Shizuku 的授权应用管理中允许本应用后重新检测");return;
                    }
                    // Keep the requested operation until the asynchronous
                    // permission result arrives. A denial never triggers su
                    // or retries a potentially completed write implicitly.
                    pendingAction=action;pendingWidth=width;permissionPending=true;
                    setBusy(true);state.setText("等待 Shizuku 授权");
                    showMessage("请在 Shizuku 提示中允许本应用。",true);
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);return;
                }
            } catch(RuntimeException error) {
                permissionPending=false;setBusy(false);
                android.util.Log.w("MainActivity","Shizuku authorization failed",error);
                authorizationFailed("Shizuku 授权失败："+error.getMessage());return;
            }
        }
        executeAction(action,width);
    }

    private void executeAction(String action,Integer width) {
        if(isDestroyed()) return;
        PrivilegedGateway selected=gateway;
        setBusy(true);state.setText(action.equals("status")?"正在读取配置与校验资源…":"正在备份、校验并处理配置…");
        showMessage(action.equals("status")?(useShizuku?"正在通过 Shizuku 读取并校验资源。":"首次使用请在 Root 管理器提示中授予权限。"):
            "请稍候，完成后会全屏启动游戏。",true);
        worker.submit(()-> {
            JSONObject result;
            try { result=selected.execute(action,width); }
            catch(Exception e) {
                android.util.Log.e("MainActivity","Privileged operation failed: "+action,e);
                result=new JSONObject();try { result.put("ok",false).put("error",e.getMessage()); } catch(JSONException ignored) {}
            }
            JSONObject output=result;
            runOnUiThread(()-> {
                if(isDestroyed()) return;
                setBusy(false);
                updateAuthorizationStatus();
                try { diagnostics.setText(output.toString(2)); } catch(JSONException ignored) {diagnostics.setText(output.toString());}
                if(!output.optBoolean("ok")) {
                    ready=false;state.setText("未完成 · 详见下方诊断");showMessage(output.optString("error","操作失败"),false);updateEnabled();return;
                }
                latest=output;ready=true;
                screenWidth=output.optInt("screenWidth",screenWidth);screenHeight=output.optInt("screenHeight",screenHeight);
                current.setText("≈ "+output.optString("predicted"));
                String info=output.optString("gameLabel",selectedGame.label)+" "+output.optString("version")+" · "+output.optString("gpu");
                if(output.optInt("grade",-1)!=output.optInt("highestGrade")) info+="\n请在游戏内选择最高可用的分辨率档位。";
                details.setText(info);state.setText("已连接 · 资源校验通过");
                showMessage(output.optString("message","配置已读取。选择目标后应用即可。"),true);
                resolutionSlider.setMin(0);resolutionSlider.setMax(screenWidth);
                resolutionSlider.setMin(ResolutionSpec.minimumLongEdge(screenWidth));
                refreshPresets();resolutionSlider.setKeyProgressIncrement(1);updatePreview();updateEnabled();
            });
        });
    }

    private void addAuthorization(LinearLayout body) {
        LinearLayout section=card(body,16);add(section,label("授权方式",16,TEXT,true),0);
        authorization=new RadioGroup(this);authorization.setOrientation(LinearLayout.HORIZONTAL);
        rootMethod=new RadioButton(this);rootMethod.setId(View.generateViewId());rootMethod.setText("Root");rootMethod.setTextColor(TEXT);
        shizukuMethod=new RadioButton(this);shizukuMethod.setId(View.generateViewId());shizukuMethod.setText("Shizuku");shizukuMethod.setTextColor(TEXT);
        rootMethod.setButtonTintList(ColorStateList.valueOf(ACCENT));shizukuMethod.setButtonTintList(ColorStateList.valueOf(ACCENT));
        authorization.addView(rootMethod,new RadioGroup.LayoutParams(0,-2,1));
        authorization.addView(shizukuMethod,new RadioGroup.LayoutParams(0,-2,1));
        authorization.check(useShizuku?shizukuMethod.getId():rootMethod.getId());add(section,authorization,8);
        authorizationStatus=label("",13,MUTED,false);add(section,authorizationStatus,8);updateAuthorizationStatus();
        Button connect=button("授权并检测",false);connect.setOnClickListener(v->run("status",null));add(section,connect,10);
        authorization.setOnCheckedChangeListener((group,id)-> {
            useShizuku=id==shizukuMethod.getId();selectGateway();
            getPreferences(MODE_PRIVATE).edit().putString("authorization",useShizuku?"shizuku":"root").apply();
            latest=null;ready=false;current.setText("待检测");updateEnabled();
            updateAuthorizationStatus();run("status",null);
        });
    }

    private void addGameSelection(LinearLayout body) {
        LinearLayout section=card(body,16);add(section,label("游戏版本",16,TEXT,true),0);
        gameSelection=new RadioGroup(this);gameSelection.setOrientation(LinearLayout.HORIZONTAL);
        for(GameTarget game:GameTarget.values()) {
            RadioButton choice=new RadioButton(this);choice.setId(View.generateViewId());choice.setTag(game);
            choice.setText(game.label);choice.setTextColor(TEXT);choice.setButtonTintList(ColorStateList.valueOf(ACCENT));
            gameSelection.addView(choice,new RadioGroup.LayoutParams(0,-2,1));
            if(game==selectedGame) gameSelection.check(choice.getId());
        }
        add(section,gameSelection,8);
        gameSelection.setOnCheckedChangeListener((group,id)-> {
            selectedGame=(GameTarget)group.findViewById(id).getTag();selectGateway();
            getPreferences(MODE_PRIVATE).edit().putString("gamePackage",selectedGame.packageName).apply();
            latest=null;ready=false;current.setText("待检测");details.setText(selectedGame.label+" · 读取中");
            updateEnabled();run("status",null);
        });
    }

    private void selectGateway() {gateway=useShizuku?new ShizukuGateway(this,selectedGame):new RootGateway(this,selectedGame);}

    private void updateAuthorizationStatus() {
        if(isDestroyed() || authorizationStatus==null) return;
        String text="Root · 首次使用需在 Root 管理器中允许。";
        if(useShizuku) {
            try {
                if(!Shizuku.pingBinder()) text="Shizuku 未运行，请先启动后授权。";
                else if(Shizuku.isPreV11()) text="请更新 Shizuku 后授权。";
                else if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) text="Shizuku 已运行，点击授权并检测。";
                else text="Shizuku 已授权 · "+(Shizuku.getUid()==0?"Root 模式":"ADB 模式");
            } catch(RuntimeException error) {text="Shizuku 状态读取失败："+error.getMessage();}
        }
        authorizationStatus.setText(text);
    }

    private void authorizationFailed(String message) {
        ready=false;state.setText("等待授权 · "+(useShizuku?"Shizuku":"Root"));showMessage(message,false);updateEnabled();
        try {diagnostics.setText(new JSONObject().put("ok",false).put("authorization",useShizuku?"Shizuku":"Root")
            .put("error",message).toString(2));} catch(JSONException ignored) { }
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
            int minimum=ResolutionSpec.minimumLongEdge(screenWidth);
            rangeMinimum.setText(minimum+" × "+ResolutionSpec.forLongEdge(minimum,screenWidth,screenHeight).height);
            rangeMaximum.setText(screenWidth+" × "+screenHeight);
            String text="拖动后点「应用」生效，按屏幕比例保持画面形状。";
            if(latest!=null) {
                ResolutionSpec old=new ResolutionSpec(latest.optInt("targetWidth"),latest.optInt("targetHeight"));
                double ratio=Math.pow(spec.scale(screenWidth,screenHeight)/old.scale(screenWidth,screenHeight),2);
                text+="\n像素量约为当前的 "+Math.round(ratio*100)+"%";
            }
            preview.setText(text);preview.setTextColor(ACCENT);
        } catch(Exception e) {preview.setText(e.getMessage());preview.setTextColor(MUTED);}
    }
    private void addPresets(LinearLayout selection) {
        add(selection,label("常用档位",13,MUTED,false),14);
        presetGrid=new GridLayout(this);presetGrid.setColumnCount(3);add(selection,presetGrid,4);
        refreshPresets();
    }
    private void refreshPresets() {
        actions.removeAll(presetWidths.keySet());presetWidths.clear();presetGrid.removeAllViews();
        int[] widths=ResolutionSpec.presetLongEdges(screenWidth);
        for(int i=0;i<widths.length;i++) {
            int pixels=widths[i];
            int shortEdge=(int)Math.round((double)pixels*screenHeight/screenWidth);
            Button preset=button(pixels+" × "+shortEdge,false);preset.setTextSize(13);
            presetWidths.put(preset,pixels);
            preset.setOnClickListener(v-> {
                resolutionSlider.setProgress(presetWidths.get(preset));rememberSelection();updatePreview();
            });
            GridLayout.LayoutParams params=new GridLayout.LayoutParams();params.width=0;
            params.columnSpec=GridLayout.spec(i%3,1f);params.rowSpec=GridLayout.spec(i/3);
            params.setMargins(dp(i%3==0?0:4),dp(4),dp(i%3==2?0:4),dp(4));presetGrid.addView(preset,params);
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
        rootMethod.setEnabled(!busy);shizukuMethod.setEnabled(!busy);
        if(gameSelection!=null) for(int i=0;i<gameSelection.getChildCount();i++) gameSelection.getChildAt(i).setEnabled(!busy);
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
    @Override protected void onResume() {super.onResume();updateAuthorizationStatus();}
    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        Shizuku.removeBinderReceivedListener(binderReceived);Shizuku.removeBinderDeadListener(binderDead);
        super.onDestroy();worker.shutdown();
    }
}
