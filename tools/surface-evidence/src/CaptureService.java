package io.github.x647368maker.surfaceprobe;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

public final class CaptureService extends Service {
    static final String UPDATE="io.github.x647368maker.surfaceprobe.UPDATE";
    static final AtomicBoolean running=new AtomicBoolean(false);
    static volatile String status="尚未取证";
    static volatile long deadline=0;
    static volatile boolean stopRequested=false;
    private static final String CHANNEL="capture";
    public IBinder onBind(Intent i){return null;}
    private Notification notification(String s){
        Intent i=new Intent(this,MainActivity.class);
        PendingIntent p=PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("图层专项取证").setContentText(s).setContentIntent(p).setOngoing(true).build();
    }
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(!running.compareAndSet(false,true))return START_NOT_STICKY;
        final int seconds=TraceConfig.duration(i==null?60:i.getIntExtra("seconds",60));
        stopRequested=false;deadline=0;
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"限时取证",NotificationManager.IMPORTANCE_LOW));
        try{
            if(Build.VERSION.SDK_INT>=34)startForeground(31,notification("检测数据源"),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(31,notification("检测数据源"));
        }catch(RuntimeException e){running.set(false);getSharedPreferences("capture",0).edit().putString("status","前台服务不可用："+e.getClass().getSimpleName()).commit();sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));stopSelf();return START_NOT_STICKY;}
        new Thread(()->capture(seconds),"sf-capture").start();
        return START_NOT_STICKY;
    }
    private void update(String s){
        status=s;getSharedPreferences("capture",0).edit().putString("status",s).apply();
        sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
        try{getSystemService(NotificationManager.class).notify(31,notification(s));}catch(RuntimeException ignored){}
    }
    // All commands are compile-time constants. No shell command is supplied by an intent or the UI.
    private int run(String command,String input,File out,File err,int seconds)throws Exception{
        java.lang.Process process=new ProcessBuilder("su","-c",command).redirectOutput(out).redirectError(err).start();
        try {
            try(OutputStream stdin=process.getOutputStream()){if(input!=null)stdin.write(input.getBytes(StandardCharsets.UTF_8));}
            if(!process.waitFor(seconds,TimeUnit.SECONDS)){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();throw new IOException("命令超时");}
            return process.exitValue();
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
    private static String read(File f,int limit)throws IOException{
        if(!f.isFile())return "";
        try(InputStream in=new FileInputStream(f)){byte[] b=new byte[limit];int k=0,n;while(k<limit&&(n=in.read(b,k,limit-k))>0)k+=n;return new String(b,0,k,StandardCharsets.UTF_8);}
    }
    private static boolean has(String q,String name){
        return java.util.regex.Pattern.compile("(?<![A-Za-z0-9_.])"+java.util.regex.Pattern.quote(name)+"(?![A-Za-z0-9_.])").matcher(q).find();
    }
    private void capture(int seconds){
        File dir=new File(getFilesDir(),"capture-work");dir.mkdirs();
        File probe=new File(dir,"root.txt"),query=new File(dir,"query.txt"),qe=new File(dir,"query-stderr.txt"),err=new File(dir,"stderr.txt"),pilot=new File(dir,"pilot.pftrace"),trace=new File(dir,"trace.pftrace"),cfgFile=new File(dir,"config.pbtxt"),report=new File(dir,"report.txt"),tmp=new File(dir,"result.zip"),pre=new File(dir,"before.winscope"),mid=new File(dir,"middle.winscope"),post=new File(dir,"after.winscope"),cmd=new File(dir,"command.txt");
        File[] all={probe,query,qe,err,pilot,trace,cfgFile,report,tmp,pre,mid,post,cmd};
        for(File f:all)f.delete();
        String key="awprobe_"+java.util.UUID.randomUUID().toString().replace("-","");
        String remote="/data/misc/perfetto-traces/"+key+".pftrace";
        boolean root=false,session=false;
        String result="取证未完成，请导出检测报告。";
        StringBuilder note=new StringBuilder("AW Surface evidence 1.1\nDevice: "+Build.MANUFACTURER+" "+Build.MODEL+" Android "+Build.VERSION.RELEASE+" API "+Build.VERSION.SDK_INT+"\nrequested_seconds="+seconds+"\n");
        try {
            update("正在检查 Root 权限…");
            int rc=run("exec /system/bin/id -u",null,probe,err,45);
            note.append("root_exit=").append(rc).append("\n");
            if(rc!=0||!read(probe,256).trim().equals("0"))throw new IOException("未取得 Root，请检查 KernelSU 授权");
            root=true;
            update("正在检测 Perfetto 数据源…");
            try {rc=run("exec /system/bin/perfetto --query",null,query,qe,12);note.append("query_exit=").append(rc).append("\n");}
            catch(Exception e){note.append("query_error=").append(e.getMessage()).append("\n");}
            String q=read(query,2*1024*1024);
            boolean layers=has(q,TraceConfig.LAYERS),tx=has(q,TraceConfig.TRANSACTIONS),frames=has(q,TraceConfig.FRAME),shell=has(q,TraceConfig.SHELL);
            note.append("query layers=").append(layers).append(" transactions=").append(tx).append(" frame=").append(frames).append(" shell=").append(shell).append("\n");
            // Do not assume query omission proves that a producer is absent.
            update("正在实际探测图层能力（3 秒）…");
            try{
                rc=run("exec /system/bin/perfetto -c - --txt -o -",TraceConfig.make(3,true,true,frames,shell,false),pilot,err,15);
                TraceCheck.Count c=TraceCheck.inspect(pilot);
                note.append("pilot_exit=").append(rc).append(" ").append(c).append("\n");
                note.append("pilot_stderr=").append(read(err,8192)).append("\n");
                if(rc==0&&c.valid){layers|=c.layers>0;tx|=c.transactions>0;}
            }catch(Exception e){note.append("pilot_error=").append(e.getMessage()).append("\n");}
            boolean continuous=false;
            if(layers||tx) {
                String cfg=TraceConfig.make(seconds,layers,tx,frames,shell,true);
                try(OutputStream o=new FileOutputStream(cfgFile)){o.write(cfg.getBytes(StandardCharsets.UTF_8));}
                rc=run("umask 077; exec /system/bin/perfetto -c - --txt --detach="+key+" -o "+remote,cfg,cmd,err,15);
                note.append("detach_exit=").append(rc).append("\n");
                if(rc==0){
                    session=true;continuous=true;
                    deadline=SystemClock.elapsedRealtime()+seconds*1000L;
                    note.append("start_wall_ms=").append(System.currentTimeMillis()).append(" start_elapsed_ns=").append(SystemClock.elapsedRealtimeNanos()).append("\n");
                    update("正在取证（连续图层模式）：切回台前调度复现异常");
                    while(!stopRequested&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(200);
                    deadline=0;update("正在停止并保存…");
                    if(!stopRequested)Thread.sleep(2000);
                    int stop=run("exec /system/bin/perfetto --attach="+key+" --stop",null,cmd,err,15);
                    note.append("stop_exit=").append(stop).append(" manual=").append(stopRequested).append("\n");
                    // A natural timeout may already have ended the session.
                    if(stop==0||!stopRequested)session=false;
                    rc=run("exec /system/bin/cat "+remote,null,trace,err,20);
                    TraceCheck.Count c=TraceCheck.inspect(trace);
                    note.append("read_exit=").append(rc).append(" bytes=").append(trace.length()).append(" ").append(c).append("\n");
                    if(rc==0&&c.useful())result="连续取证结束：图层及事务初检通过，请导出。";
                    else if(rc==0&&c.valid&&(c.layers>0||c.transactions>0))result="部分连续取证完成（图层或事务缺失），请导出。";
                    else result="连续取证已结束，但没有完整图层数据，请导出。";
                }else note.append("detach_stderr=").append(read(err,8192)).append("\n");
            }
            if(!continuous){
                // Coarse read-only fallback. Never present this as per-frame tracing.
                update("连续图层不可用，正在检测兼容快照…");
                int first=run("exec /system/bin/dumpsys -t 5 SurfaceFlinger --proto",null,pre,err,10);
                note.append("snapshot_before_exit=").append(first).append(" elapsed_ns=").append(SystemClock.elapsedRealtimeNanos()).append(" bytes=").append(pre.length()).append("\n");
                if(first==0&&pre.length()>128){
                    deadline=SystemClock.elapsedRealtime()+seconds*1000L;
                    note.append("snapshot_start_elapsed_ns=").append(SystemClock.elapsedRealtimeNanos()).append("\n");
                    update("正在取证（兼容快照，非逐帧）：切回台前调度复现");
                    boolean middle=false;
                    while(!stopRequested&&SystemClock.elapsedRealtime()<deadline){
                        if(!middle&&deadline-SystemClock.elapsedRealtime()<=seconds*500L){
                            middle=true;
                            int mr=run("exec /system/bin/dumpsys -t 5 SurfaceFlinger --proto",null,mid,err,10);
                            note.append("snapshot_middle_exit=").append(mr).append(" elapsed_ns=").append(SystemClock.elapsedRealtimeNanos()).append(" bytes=").append(mid.length()).append("\n");
                        }
                        Thread.sleep(200);
                    }
                    deadline=0;update("正在保存兼容快照…");
                    int last=run("exec /system/bin/dumpsys -t 5 SurfaceFlinger --proto",null,post,err,10);
                    note.append("snapshot_after_exit=").append(last).append(" elapsed_ns=").append(SystemClock.elapsedRealtimeNanos()).append(" bytes=").append(post.length()).append("\n");
                    result="兼容快照采集结束（非逐帧，不代表已抓住突变），请导出。";
                }else result="ColorOS 未提供可用连续图层或快照，请导出诊断 ZIP。";
            }
        }catch(Exception e){note.append("error=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");result="取证失败："+e.getMessage()+"。请导出检测报告。";}
        finally{
            deadline=0;
            note.append("end_elapsed_ns=").append(SystemClock.elapsedRealtimeNanos()).append("\n");
            try{note.append("stderr=").append(read(err,8192)).append("\n");}catch(Exception ignored){}
            if(session)try{run("exec /system/bin/perfetto --attach="+key+" --stop",null,cmd,err,12);}catch(Exception ignored){}
            if(root)try{run("exec /system/bin/rm -f "+remote,null,cmd,err,5);}catch(Exception ignored){}
            try{
                note.append("Only metadata; capture overhead may affect timing. Packet counts or snapshots do not prove pixel continuity.\n");
                try(OutputStream o=new FileOutputStream(report)){o.write(note.toString().getBytes(StandardCharsets.UTF_8));}
                try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(tmp))){
                    byte[] b=new byte[65536];
                    for(File f:new File[]{report,cfgFile,trace,pilot,query,qe,pre,mid,post}){
                        if(!f.isFile()||f.length()==0||f.length()>150L*1024*1024)continue;
                        z.putNextEntry(new ZipEntry(f.getName()));
                        try(InputStream in=new FileInputStream(f)){int n;while((n=in.read(b))!=-1)z.write(b,0,n);}
                        z.closeEntry();
                    }
                }
                if(!tmp.renameTo(new File(getFilesDir(),"latest.zip")))throw new IOException("保存失败");
            }catch(Exception e){result="保存失败，请勿使用旧报告："+e.getMessage();new File(getFilesDir(),"latest.zip").delete();}
            for(File f:all)f.delete();
            update(result);running.set(false);sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        }
    }
}
