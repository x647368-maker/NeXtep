package io.github.x647368maker.surfaceprobe;
final class TraceConfig {
    static final String LAYERS="android.surfaceflinger.layers", TRANSACTIONS="android.surfaceflinger.transactions", FRAME="android.surfaceflinger.frametimeline", SHELL="com.android.wm.shell.transition";
    static int duration(int seconds){return seconds==30||seconds==60||seconds==120||seconds==180?seconds:60;}
    static String make(boolean frames,boolean shell){return make(30,true,true,frames,shell,false);}
    static String make(int secs,boolean layers,boolean tx,boolean frames,boolean shell,boolean detached){
        int ms=(secs==3?3:duration(secs))*1000;
        return "duration_ms: "+ms+"\n"+
          (detached?"write_into_file: true\nfile_write_period_ms: 2000\nmax_file_size_bytes: 100663296\n":"")+
          "buffers { size_kb: 32768 fill_policy: RING_BUFFER }\nbuffers { size_kb: 16384 fill_policy: RING_BUFFER }\nbuffers { size_kb: 4096 fill_policy: RING_BUFFER }\n"+
          (layers?"data_sources { config { name: \""+LAYERS+"\" target_buffer: 0 surfaceflinger_layers_config { mode: MODE_ACTIVE trace_flags: TRACE_FLAG_COMPOSITION trace_flags: TRACE_FLAG_BUFFERS trace_flags: TRACE_FLAG_VIRTUAL_DISPLAYS } } }\n":"")+
          (tx?"data_sources { config { name: \""+TRANSACTIONS+"\" target_buffer: 1 surfaceflinger_transactions_config { mode: MODE_ACTIVE } } }\n":"")+
          (frames?"data_sources { config { name: \""+FRAME+"\" target_buffer: 2 } }\n":"")+
          (shell?"data_sources { config { name: \""+SHELL+"\" target_buffer: 2 } }\n":"");
    }
}
