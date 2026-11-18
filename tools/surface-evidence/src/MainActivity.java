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
    private Button start,export,cancel,trigger;
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
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xfff5f7fb);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(24),dp(24),dp(24),dp(24));
        scroll.addView(panel);setContentView(scroll);
        TextView title=text("单次交换取证",28);title.setTypeface(null,Typeface.BOLD);panel.addView(title);
        panel.addView(text("台前调度 · 精确取证 / V1.2",14));
        panel.addView(text("1. 允许通知、Root，点击准备。\n2. 打开台前调度，把应用全部摆好，提前打开录屏。\n3. 下拉通知，点击「开始单次取证」，立即收回通知栏。\n4. 5 秒后先短振动一次：马上只交换一次。第二次双振动表示采集结束，请勿继续交换。\n5. 回来导出 ZIP，同时发送录屏和台前调度诊断 TXT。",16));
        status=text("",16);status.setPadding(dp(16),dp(18),dp(16),dp(18));status.setBackgroundColor(Color.WHITE);panel.addView(status);
        start=new Button(this);start.setText("准备单次取证");panel.addView(start);
        trigger=new Button(this);trigger.setText("备用：8秒后自动开始取证");panel.addView(trigger);
        cancel=new Button(this);cancel.setText("取消准备");panel.addView(cancel);
        export=new Button(this);export.setText("导出单次取证 ZIP");panel.addView(export);
        start.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("准备一次性取证")
            .setMessage("申请 Root，记录系统图层元数据及时间戳。不会截图、记录触控文字或修改系统。请避开敏感页面；设备过热请不要测试。准备限时180秒，正式采集4秒。")
            .setNegativeButton("取消",null).setPositiveButton("准备",(d,w)->{
                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},17);
                try{startForegroundService(new Intent(this,CaptureService.class));}
                catch(RuntimeException e){Toast.makeText(this,"无法启动："+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show();}
            }).show());
        trigger.setOnClickListener(v->{
            startService(new Intent(this,CaptureService.class).setAction(CaptureService.FIRE_UI).putExtra("token",CaptureService.token));
            Toast.makeText(this,"8秒后短振动，立即返回已准备好的工作台",Toast.LENGTH_LONG).show();
        });
        cancel.setOnClickListener(v->startService(new Intent(this,CaptureService.class).setAction(CaptureService.CANCEL).putExtra("token",CaptureService.token)));
        export.setOnClickListener(v->{
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE,"台前调度-单次取证-"+System.currentTimeMillis()+".zip");startActivityForResult(i,42);
        });
        panel.addView(text("只采集约4秒，避免原来长时记录因容量上限错过实际交换。通知按钮优先使用；如果通知不可用，使用备用按钮。仅在捕获到所需图层时可作为后续分析证据。",14));
        refresh();
    }
    @Override protected void onStart(){super.onStart();if(Build.VERSION.SDK_INT>=33)registerReceiver(updated,new IntentFilter(CaptureService.UPDATE),Context.RECEIVER_NOT_EXPORTED);else registerReceiver(updated,new IntentFilter(CaptureService.UPDATE));refresh();}
    @Override protected void onStop(){unregisterReceiver(updated);super.onStop();}
    @Override protected void onResume(){super.onResume();refresh();}
    private void refresh(){
        if(status==null)return;
        boolean busy=CaptureService.running.get();
        status.setText(busy?CaptureService.status:getSharedPreferences("capture",0).getString("status","尚未取证"));
        start.setEnabled(!busy);cancel.setEnabled(busy&&CaptureService.armed);
        trigger.setEnabled(busy&&CaptureService.armed);
        export.setEnabled(!busy && new File(getFilesDir(),"latest.zip").isFile());
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req!=42||result!=RESULT_OK||data==null||data.getData()==null)return;
        final android.net.Uri uri=data.getData();start.setEnabled(false);export.setEnabled(false);
        new Thread(()->{
            String msg="已导出。请将 ZIP 和对应录屏、TXT 一起发送。";
            try(InputStream in=new FileInputStream(new File(getFilesDir(),"latest.zip"));OutputStream out=getContentResolver().openOutputStream(uri,"w")){
                if(out==null)throw new IOException("no output");
                byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }catch(Exception e){msg="导出失败："+e.getClass().getSimpleName();}
            final String m=msg;runOnUiThread(()->{refresh();Toast.makeText(this,m,Toast.LENGTH_LONG).show();});
        },"export").start();
    }
}
