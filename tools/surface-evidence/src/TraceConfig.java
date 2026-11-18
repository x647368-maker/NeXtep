package io.github.x647368maker.surfaceprobe;

final class TraceConfig {
    static final String LAYERS="android.surfaceflinger.layers", TRANSACTIONS="android.surfaceflinger.transactions", FRAME="android.surfaceflinger.frametimeline", SHELL="com.android.wm.shell.transition";
    static final int CAPTURE_MS=4000;
    // Single continuous uncompressed recording. No detached remote files or
    // multi-attempt traces; keep sufficient capacity for a 4s ColorOS burst.
    static String make(boolean tx,boolean frames,boolean shell){
        return "duration_ms: "+CAPTURE_MS+"\n"+
          "buffers { size_kb: 98304 fill_policy: DISCARD }\n"+
          "buffers { size_kb: 8192 fill_policy: DISCARD }\n"+
          "buffers { size_kb: 4096 fill_policy: DISCARD }\n"+
          "data_sources { config { name: \""+LAYERS+"\" target_buffer: 0 surfaceflinger_layers_config { mode: MODE_ACTIVE trace_flags: TRACE_FLAG_COMPOSITION trace_flags: TRACE_FLAG_BUFFERS trace_flags: TRACE_FLAG_VIRTUAL_DISPLAYS } } }\n"+
          (tx?"data_sources { config { name: \""+TRANSACTIONS+"\" target_buffer: 1 surfaceflinger_transactions_config { mode: MODE_ACTIVE } } }\n":"")+
          (frames?"data_sources { config { name: \""+FRAME+"\" target_buffer: 2 } }\n":"")+
          (shell?"data_sources { config { name: \""+SHELL+"\" target_buffer: 2 } }\n":"");
    }
}
