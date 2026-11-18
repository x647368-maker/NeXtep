package io.github.x647368maker.surfaceprobe;

final class TraceConfig {
    static final String LAYERS="android.surfaceflinger.layers";
    static final String TRANSACTIONS="android.surfaceflinger.transactions";
    static final String FRAME="android.surfaceflinger.frametimeline";
    static final String SHELL="com.android.wm.shell.transition";
    static String make(boolean frames,boolean shell) {
        return "duration_ms: 30000\n"+
            "buffers { size_kb: 65536 fill_policy: RING_BUFFER }\n"+
            "buffers { size_kb: 32768 fill_policy: RING_BUFFER }\n"+
            "buffers { size_kb: 8192 fill_policy: RING_BUFFER }\n"+
            "data_sources { config { name: \""+LAYERS+"\" target_buffer: 0 surfaceflinger_layers_config { mode: MODE_ACTIVE trace_flags: TRACE_FLAG_COMPOSITION trace_flags: TRACE_FLAG_BUFFERS trace_flags: TRACE_FLAG_VIRTUAL_DISPLAYS } } }\n"+
            "data_sources { config { name: \""+TRANSACTIONS+"\" target_buffer: 1 surfaceflinger_transactions_config { mode: MODE_ACTIVE } } }\n"+
            (frames?"data_sources { config { name: \""+FRAME+"\" target_buffer: 2 } }\n":"")+
            (shell?"data_sources { config { name: \""+SHELL+"\" target_buffer: 2 } }\n":"");
    }
}
