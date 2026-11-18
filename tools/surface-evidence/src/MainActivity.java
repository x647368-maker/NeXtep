package io.github.x647368maker.surfaceprobe;
import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.io.*;

public final class MainActivity extends Activity {
    private TextView status;
    private Button start,export,stop;
    private Spinner duration;
    private final int[] seconds={30,60,120,180};
    private final Handler timer=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable(){public void run(){refresh();timer.postDelayed(this,500);}};
    private final BroadcastReceiver updated=new BroadcastReceiver(){
        public void onReceive(Context c,Intent i){refresh();}
    };
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(0xff25324a);t.setPadding(0,dp(8),0,dp(8));return t;}
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(0xfff5f7fb);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setNavigationBarColor(0xfff5f7fb);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);scroll.setBackgroundColor(0xfff5f7fb);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(24),dp(24),dp(24),dp(24));
        scroll.addView(panel);setContentView(scroll);
        TextView title=text("图层取证工具",28);title.setTypeface(null,Typeface.BOLD);panel.addView(title);
        panel.addView(text("台前调度 · 独立诊断 / 1.1",14));
        panel.addView(text("1. 点击开始，允许 KernelSU Root 授权。\n2. 显示正在取证后，切回台前调度；在选择的时长内复现主小窗交换异常。可同时使用系统录屏。\n3. 等自动结束，回到此应用导出 ZIP。",16));
        status=text("",16);status.setPadding(dp(16),dp(18),dp(16),dp(18));status.setBackgroundColor(Color.WHITE);panel.addView(status);
        panel.addView(text("采集时长",16));
        duration=new Spinner(this);
        duration.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"30 秒","60 秒（推荐）","120 秒","180 秒"}));
        duration.setSelection(1);panel.addView(duration);
        start=new Button(this);start.setText("开始取证");panel.addView(start);
        stop=new Button(this);stop.setText("提前结束并保存");panel.addView(stop);
        stop.setOnClickListener(v->{CaptureService.stopRequested=true;stop.setEnabled(false);});
        export=new Button(this);export.setText("导出取证 ZIP");panel.addView(export);
        start.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("开始一次性图层取证")
            .setMessage("会临时申请 Root，记录系统图层名称、位置、父子层及缓冲区元数据，可能包含应用名称，且采集本身可能影响流畅度。不会读取画面像素、输入内容或修改系统设置。文件仅保存在本机，由你手动分享。请勿在采集期间打开敏感页面。")
            .setNegativeButton("取消",null).setPositiveButton("开始",(d,w)->{
                start.setEnabled(false);status.setText("请求 Root 并检测数据源…");
                try{startForegroundService(new Intent(this,CaptureService.class).putExtra("seconds",seconds[duration.getSelectedItemPosition()]));}
                catch(RuntimeException e){status.setText("无法启动取证："+e.getClass().getSimpleName());refresh();}
            }).show());
        export.setOnClickListener(v->{
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE,"台前调度-图层取证-"+System.currentTimeMillis()+".zip");
            startActivityForResult(i,42);
        });
        panel.addView(text("不安装模块，不更换台前调度。无联网权限、无常驻采集。支持连续取证或兼容快照；快照不是逐帧证据。长时间采集可能提前达到文件上限。",14));
        refresh();
    }
    @Override protected void onStart(){super.onStart();if(Build.VERSION.SDK_INT>=33)registerReceiver(updated,new IntentFilter(CaptureService.UPDATE),Context.RECEIVER_NOT_EXPORTED);else registerReceiver(updated,new IntentFilter(CaptureService.UPDATE));refresh();timer.post(tick);}
    @Override protected void onStop(){timer.removeCallbacks(tick);unregisterReceiver(updated);super.onStop();}
    @Override protected void onResume(){super.onResume();refresh();}
    private void refresh(){
        if(status==null)return;
        status.setText(CaptureService.running.get()?CaptureService.status:getSharedPreferences("capture",0).getString("status","尚未取证"));
        long remaining=CaptureService.deadline-SystemClock.elapsedRealtime();
        if(CaptureService.running.get() && remaining>0)status.append("\n剩余 "+((remaining+999)/1000)+" 秒");
        duration.setEnabled(!CaptureService.running.get());
        stop.setEnabled(CaptureService.running.get() && remaining>0 && !CaptureService.stopRequested);
        start.setEnabled(!CaptureService.running.get());
        export.setEnabled(!CaptureService.running.get() && new File(getFilesDir(),"latest.zip").isFile());
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req!=42||result!=RESULT_OK||data==null||data.getData()==null)return;
        final android.net.Uri uri=data.getData();start.setEnabled(false);export.setEnabled(false);
        new Thread(()->{
            String msg="已导出。请将 ZIP 文件和对应录屏发给分析方。";
            try(InputStream in=new FileInputStream(new File(getFilesDir(),"latest.zip"));OutputStream out=getContentResolver().openOutputStream(uri,"w")){
                if(out==null)throw new IOException("no output");
                byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }catch(Exception e){msg="导出失败："+e.getClass().getSimpleName();}
            final String m=msg;runOnUiThread(()->{refresh();Toast.makeText(this,m,Toast.LENGTH_LONG).show();});
        },"export").start();
    }
}
