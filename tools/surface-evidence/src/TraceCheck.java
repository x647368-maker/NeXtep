package io.github.x647368maker.surfaceprobe;

import java.io.*;

final class TraceCheck {
    static final class Count {
        long packets,layers,transactions;
        boolean valid;
        boolean useful(){return valid && layers>=2 && transactions>=1;}
        public String toString(){return "valid="+valid+" packets="+packets+" layer_snapshots="+layers+" transaction_packets="+transactions;}
    }
    private static long varint(RandomAccessFile r,long end)throws IOException {
        long out=0;
        for(int i=0;i<10;i++){
            if(r.getFilePointer()>=end)throw new EOFException();
            int b=r.readUnsignedByte();
            if(i==9 && (b&254)!=0)throw new IOException("varint overflow");
            out|=(long)(b&127)<<(i*7);
            if((b&128)==0)return out;
        }
        throw new IOException("bad varint");
    }
    private static long sized(RandomAccessFile r,long end)throws IOException {
        long n=varint(r,end);
        if(n<0 || n>end-r.getFilePointer())throw new EOFException("invalid length");
        return r.getFilePointer()+n;
    }
    private static void skip(RandomAccessFile r,long end,int wire)throws IOException {
        long n;
        switch(wire){case 0:varint(r,end);return;case 1:n=8;break;case 2:r.seek(sized(r,end));return;case 5:n=4;break;default:throw new IOException("unknown wire");}
        if(n>end-r.getFilePointer())throw new EOFException();
        r.seek(r.getFilePointer()+n);
    }
    static Count inspect(File f) {
        Count c=new Count();
        if(!f.isFile() || f.length()==0 || f.length()>150L*1024*1024)return c;
        try(RandomAccessFile r=new RandomAccessFile(f,"r")){
            long end=r.length();
            while(r.getFilePointer()<end){
                long tag=varint(r,end);
                if(tag!=10){throw new IOException("not uncompressed Perfetto Trace packet");}
                long stop=sized(r,end);
                c.packets++;
                while(r.getFilePointer()<stop){
                    long field=varint(r,stop);
                    if((field>>>3)==0)throw new IOException("zero field");
                    int wire=(int)(field&7);
                    if(wire==2 && (field>>>3)==93){long next=sized(r,stop);if(next>r.getFilePointer())c.layers++;r.seek(next);}
                    else if(wire==2 && (field>>>3)==94){long next=sized(r,stop);if(next>r.getFilePointer())c.transactions++;r.seek(next);}
                    else skip(r,stop,wire);
                }
            }
            c.valid=c.packets>0;
        }catch(IOException|RuntimeException e){c.valid=false;}
        return c;
    }
}
