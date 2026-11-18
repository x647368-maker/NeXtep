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
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"限时取证",NotificationManager.IMPORTANCE_LOW));
        try{
            if(Build.VERSION.SDK_INT>=34)startForeground(31,notification("检测数据源"),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(31,notification("检测数据源"));
        }catch(RuntimeException e){running.set(false);getSharedPreferences("capture",0).edit().putString("status","前台服务不可用："+e.getClass().getSimpleName()).commit();sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));stopSelf();return START_NOT_STICKY;}
        new Thread(this::capture,"sf-capture").start();
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
    private void capture(){
        File dir=new File(getFilesDir(),"capture-work");
        dir.mkdirs();
        File probe=new File(dir,"probe.txt"),query=new File(dir,"query.txt"),errors=new File(dir,"stderr.txt"),trace=new File(dir,"trace.pftrace"),config=new File(dir,"config.pbtxt"),report=new File(dir,"report.txt"),temp=new File(dir,"result.zip");
        for(File f:new File[]{probe,query,errors,trace,config,report,temp})f.delete();
        StringBuilder note=new StringBuilder("AW Surface evidence 1.0\nDevice: "+Build.MANUFACTURER+" "+Build.MODEL+" Android "+Build.VERSION.RELEASE+" API "+Build.VERSION.SDK_INT+"\n");
        String result="取证失败，可导出检测报告";
        try{
            update("正在检查 Root 权限…");
            int rc=run("exec /system/bin/id -u",null,probe,errors,45);
            note.append("root_exit=").append(rc).append("\n");
            if(rc!=0||!read(probe,256).trim().equals("0"))throw new IOException("未取得 Root，请检查 KernelSU 授权");
            update("正在检测 Perfetto 数据源…");
            rc=run("exec /system/bin/perfetto --query",null,query,errors,15);
            note.append("query_exit=").append(rc).append("\n");
            String q=read(query,2*1024*1024);
            boolean layers=has(q,TraceConfig.LAYERS),transactions=has(q,TraceConfig.TRANSACTIONS),frames=has(q,TraceConfig.FRAME),shell=has(q,TraceConfig.SHELL);
            note.append("layers=").append(layers).append(" transactions=").append(transactions).append(" frametimeline=").append(frames).append(" shell=").append(shell).append("\n");
            if(rc!=0||!layers||!transactions)throw new IOException("系统未公开必要图层/事务数据源");
            String cfg=TraceConfig.make(frames,shell);
            try(OutputStream o=new FileOutputStream(config)){o.write(cfg.getBytes(StandardCharsets.UTF_8));}
            note.append("start_wall_ms=").append(System.currentTimeMillis()).append(" start_elapsed_ns=").append(SystemClock.elapsedRealtimeNanos()).append("\n");
            update("正在取证：现在切回台前调度，30 秒内复现问题。");
            rc=run("exec /system/bin/perfetto -c - --txt -o -",cfg,trace,errors,55);
            note.append("capture_exit=").append(rc).append(" end_wall_ms=").append(System.currentTimeMillis()).append(" end_elapsed_ns=").append(SystemClock.elapsedRealtimeNanos()).append("\n");
            TraceCheck.Count counts=TraceCheck.inspect(trace);
            note.append("trace_bytes=").append(trace.length()).append(" ").append(counts).append("\n");
            result=rc==0&&counts.useful()?"取证结束：图层和事务数据初检通过，可以导出 ZIP。":"取证结束，但图层或事务数据不完整，请导出 ZIP 供分析。";
        }catch(Exception e){
            note.append("error=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
            result="取证未完成："+e.getMessage()+"。请导出检测报告。";
        }finally{
            try{
                note.append("stderr (limited):\n").append(read(errors,8192)).append("\n");
                note.append("Only metadata; capture overhead may affect timing. Packet counts do not prove visual presentation.\n");
                try(OutputStream o=new FileOutputStream(report)){o.write(note.toString().getBytes(StandardCharsets.UTF_8));}
                try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(temp))){
                    byte[] b=new byte[65536];
                    for(File f:new File[]{report,config,trace}){
                        if(!f.isFile()||f.length()==0||f.length()>150L*1024*1024)continue;
                        z.putNextEntry(new ZipEntry(f.getName()));
                        try(InputStream in=new FileInputStream(f)){int n;while((n=in.read(b))!=-1)z.write(b,0,n);}
                        z.closeEntry();
                    }
                }
                File dest=new File(getFilesDir(),"latest.zip");
                if(!temp.renameTo(dest))throw new IOException("保存失败");
            }catch(Exception e){result="无法保存本轮取证；请勿将上一份 ZIP 当作本轮。"+e.getMessage();new File(getFilesDir(),"latest.zip").delete();}
            for(File f:new File[]{probe,query,errors,trace,config,report,temp})f.delete();
            update(result);running.set(false);sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        }
    }
}
