package io.github.x647368maker.surfaceprobe;
import java.io.*;
public final class SelfTest {
 static byte[] make(int layers,int tx)throws Exception {
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  for(int i=0;i<layers+tx;i++){int id=i<layers?93:94;int t=id*8+2;out.write(10);out.write(4);out.write((t&127)|128);out.write(t>>7);out.write(1);out.write(8);}
  return out.toByteArray();
 }
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  File f=File.createTempFile("awtrace",".pftrace");
  try{
   try(FileOutputStream o=new FileOutputStream(f)){o.write(make(2,1));}
   TraceCheck.Count c=TraceCheck.inspect(f);check(c.useful()&&c.layers==2&&c.transactions==1);
   try(FileOutputStream o=new FileOutputStream(f)){o.write(make(2,0));}
   check(!TraceCheck.inspect(f).useful());
   try(FileOutputStream o=new FileOutputStream(f)){o.write(new byte[]{10,100,1});}
   check(!TraceCheck.inspect(f).valid);
   String cfg=TraceConfig.make(true,true);
   check(cfg.contains("duration_ms: 30000")&&cfg.contains("TRACE_FLAG_BUFFERS")&&cfg.contains("TRACE_FLAG_VIRTUAL_DISPLAYS")&&cfg.contains(TraceConfig.TRANSACTIONS));
   check(!TraceConfig.make(false,false).contains(TraceConfig.FRAME));
   check(!cfg.contains("android.input")&&!cfg.contains("android.log")&&!cfg.contains("TRACE_FLAG_INPUT"));
   System.out.println("PASS trace packet admission, truncated trace rejection, bounded metadata-only config");
  }finally{f.delete();}
 }
}
