package io.github.x647368maker.surfaceprobe;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

public final class CaptureService extends Service {
    static final String UPDATE="io.github.x647368maker.surfaceprobe.UPDATE", FIRE="fire", FIRE_UI="fire_ui", CANCEL="cancel";
    static final AtomicBoolean running=new AtomicBoolean(false);
    static volatile String status="尚未取证";
    static volatile boolean armed=false;
    static volatile long token=0;
    private volatile CountDownLatch trigger;
    private volatile int delay=0;
    private volatile java.lang.Process child;
    private volatile boolean destroyed=false;
    private static final String CHANNEL="single_capture";
    public IBinder onBind(Intent i){return null;}
    private PendingIntent action(String name,int code){
        return PendingIntent.getService(this,code,new Intent(this,CaptureService.class).setAction(name).putExtra("token",token),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private Notification notification(String s){
        PendingIntent p=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_info_details)
          .setContentTitle("单次交换取证").setContentText(s).setStyle(new Notification.BigTextStyle().bigText(s)).setContentIntent(p).setOngoing(true);
        if(armed){b.addAction(new Notification.Action.Builder(null,"开始单次取证",action(FIRE,2)).build());b.addAction(new Notification.Action.Builder(null,"取消",action(CANCEL,3)).build());}
        return b.build();
    }
    @Override public int onStartCommand(Intent i,int flags,int id){
        String a=i==null?null:i.getAction();
        if(FIRE.equals(a)||FIRE_UI.equals(a)||CANCEL.equals(a)){
            if(running.get()&&armed&&i.getLongExtra("token",-1)==token){
                armed=false; delay=CANCEL.equals(a)?-1:FIRE_UI.equals(a)?8:5;
                CountDownLatch t=trigger; if(t!=null)t.countDown();
                update(delay<0?"已取消，正在保存检测信息":"即将开始，请立即收回通知栏并保持工作台静止；短振动后只交换一次。");
            }
            return START_NOT_STICKY;
        }
        if(!running.compareAndSet(false,true))return START_NOT_STICKY;
        token=SystemClock.elapsedRealtimeNanos();trigger=new CountDownLatch(1);delay=0;armed=false;destroyed=false;
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"单次取证",NotificationManager.IMPORTANCE_LOW));
        try{
            if(Build.VERSION.SDK_INT>=34)startForeground(31,notification("检测 Root 和数据源"),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(31,notification("检测 Root 和数据源"));
        }catch(RuntimeException e){running.set(false);getSharedPreferences("capture",0).edit().putString("status","前台服务不可用："+e.getClass().getSimpleName()).commit();sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));stopSelf();return START_NOT_STICKY;}
        new Thread(this::capture,"single-sf-capture").start();
        return START_NOT_STICKY;
    }
    private void update(String s){
        status=s;getSharedPreferences("capture",0).edit().putString("status",s).apply();
        sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
        try{getSystemService(NotificationManager.class).notify(31,notification(s));}catch(RuntimeException ignored){}
    }
    private void vibrate(boolean end){
        try {
            Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);
            if(v!=null && v.hasVibrator())v.vibrate(end?VibrationEffect.createWaveform(new long[]{0,120,100,120},-1):VibrationEffect.createOneShot(200,VibrationEffect.DEFAULT_AMPLITUDE));
        }catch(RuntimeException ignored){}
    }
    // All shell invocations are fixed, read-only commands. No detached trace.
    private int run(String command,String input,File out,File err,int seconds,boolean cue,StringBuilder note)throws Exception{
        if(destroyed)throw new IOException("service stopped");
        java.lang.Process process=new ProcessBuilder("su","-c",command).redirectOutput(out).redirectError(err).start();
        child=process;
        try{
            try(OutputStream stdin=process.getOutputStream()){if(input!=null)stdin.write(input.getBytes(StandardCharsets.UTF_8));}
            if(cue){
                // Start-cue timing is a host observation, not SF frame proof.
                long waitUntil=SystemClock.elapsedRealtime()+1800;
                boolean connected=false;
                while(process.isAlive()&&SystemClock.elapsedRealtime()<waitUntil){
                    if(read(err,4096).contains("Connected to the Perfetto traced service")){connected=true;break;}
                    Thread.sleep(30);
                }
                if(!process.isAlive())throw new IOException("Perfetto exited before cue");
                Thread.sleep(550);
                if(!process.isAlive())throw new IOException("Perfetto exited before cue");
                note.append("cue_connected_log=").append(connected).append(" cue_wall_ms=").append(System.currentTimeMillis()).append(" cue_elapsed_ns=").append(SystemClock.elapsedRealtimeNanos()).append("\n");
                vibrate(false);
            }
            if(!process.waitFor(seconds,TimeUnit.SECONDS)){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();throw new IOException("命令超时");}
            return process.exitValue();
        }finally{if(process.isAlive())process.destroyForcibly();if(child==process)child=null;}
    }
    private static String read(File f,int limit)throws IOException{
        if(!f.isFile())return "";
        try(InputStream in=new FileInputStream(f)){byte[] b=new byte[limit];int k=0,n;while(k<limit&&(n=in.read(b,k,limit-k))>0)k+=n;return new String(b,0,k,StandardCharsets.UTF_8);}
    }
    private static boolean has(String q,String name){
        return java.util.regex.Pattern.compile("(?<![A-Za-z0-9_.])"+java.util.regex.Pattern.quote(name)+"(?![A-Za-z0-9_.])").matcher(q).find();
    }
    private void capture(){
        File dir=new File(getFilesDir(),"capture-work");dir.mkdirs();
        File probe=new File(dir,"root.txt"),query=new File(dir,"query.txt"),qe=new File(dir,"query-stderr.txt"),err=new File(dir,"stderr.txt"),trace=new File(dir,"trace.pftrace"),cfgFile=new File(dir,"config.pbtxt"),report=new File(dir,"report.txt"),tmp=new File(dir,"result.zip");
        File[] all={probe,query,qe,err,trace,cfgFile,report,tmp};for(File f:all)f.delete();
        // A prior successful ZIP must not be mistaken for this capture.
        new File(getFilesDir(),"latest.zip").delete();
        String result="取证失败，请导出检测报告";
        StringBuilder note=new StringBuilder("AW Surface evidence 1.2 single exchange\nDevice: "+Build.MANUFACTURER+" "+Build.MODEL+" Android "+Build.VERSION.RELEASE+" API "+Build.VERSION.SDK_INT+"\nrequested_capture_ms="+TraceConfig.CAPTURE_MS+"\n");
        try{
            update("正在检查 Root 权限…");
            int rc=run("exec /system/bin/id -u",null,probe,err,45,false,note);
            note.append("root_exit=").append(rc).append("\n");
            if(rc!=0||!read(probe,256).trim().equals("0"))throw new IOException("未取得 Root，请检查 KernelSU 授权");
            update("正在检查数据源…");
            rc=run("exec /system/bin/perfetto --query",null,query,qe,12,false,note);
            String q=read(query,2*1024*1024);
            boolean layers=has(q,TraceConfig.LAYERS),tx=has(q,TraceConfig.TRANSACTIONS),frames=has(q,TraceConfig.FRAME),shell=has(q,TraceConfig.SHELL);
            note.append("query_exit=").append(rc).append(" layers=").append(layers).append(" transactions=").append(tx).append(" frames=").append(frames).append(" shell=").append(shell).append("\n");
            if(rc!=0||!layers)throw new IOException("本轮没有检测到连续图层数据源");
            String cfg=TraceConfig.make(tx,frames,shell);
            try(OutputStream o=new FileOutputStream(cfgFile)){o.write(cfg.getBytes(StandardCharsets.UTF_8));}
            armed=true;update("准备就绪。应用全部摆好后，从通知点击「开始单次取证」。");
            if(!trigger.await(180,TimeUnit.SECONDS))throw new IOException("准备超时，未采集");
            armed=false;
            if(delay<0||destroyed)throw new IOException("用户取消");
            note.append("trigger_elapsed_ns=").append(SystemClock.elapsedRealtimeNanos()).append(" delay_s=").append(delay).append("\n");
            Thread.sleep(delay*1000L);
            update("正在采集：短振动后只交换一次");
            note.append("start_wall_ms=").append(System.currentTimeMillis()).append(" start_elapsed_ns=").append(SystemClock.elapsedRealtimeNanos()).append("\n");
            rc=run("exec /system/bin/perfetto -c - --txt -o -",cfg,trace,err,14,true,note);
            note.append("capture_exit=").append(rc).append(" end_wall_ms=").append(System.currentTimeMillis()).append(" end_elapsed_ns=").append(SystemClock.elapsedRealtimeNanos()).append("\n");
            vibrate(true);
            TraceCheck.Count c=TraceCheck.inspect(trace);
            note.append("trace_bytes=").append(trace.length()).append(" ").append(c).append("\n");
            result=rc==0&&c.valid&&c.layers>=2?"单次图层采集结束，请导出（仍需核实实际交换是否在覆盖范围内）。":"取证数据不足，请导出检测报告。";
        }catch(Exception e){note.append("error=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");result="取证未完成："+e.getMessage();}
        finally{
            armed=false;
            try{note.append("stderr=").append(read(err,8192)).append("\n");}catch(Exception ignored){}
            try{
                note.append("Only metadata; request/commit/pixel presentation are different. Actual coverage and trace loss require offline verification.\n");
                try(OutputStream o=new FileOutputStream(report)){o.write(note.toString().getBytes(StandardCharsets.UTF_8));}
                try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(tmp))){
                    byte[] b=new byte[65536];
                    for(File f:new File[]{report,cfgFile,trace,query,qe}){
                        if(!f.isFile()||f.length()==0||f.length()>150L*1024*1024)continue;
                        z.putNextEntry(new ZipEntry(f.getName()));
                        try(InputStream in=new FileInputStream(f)){int n;while((n=in.read(b))!=-1)z.write(b,0,n);}
                        z.closeEntry();
                    }
                }
                if(!tmp.renameTo(new File(getFilesDir(),"latest.zip")))throw new IOException("保存失败");
            }catch(Exception e){result="保存失败："+e.getMessage();new File(getFilesDir(),"latest.zip").delete();}
            for(File f:all)f.delete();
            update(result);running.set(false);sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        }
    }
    @Override public void onDestroy(){
        destroyed=true;armed=false;CountDownLatch t=trigger;if(t!=null){delay=-1;t.countDown();}
        java.lang.Process p=child;if(p!=null&&p.isAlive())p.destroy();
        super.onDestroy();
    }
}
