package workbench2.hostruntime;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.UUID;

/** Disposable own task; no permissions, background work or third-party content. */
public final class SessionActivity extends Activity {
    private final String instance=UUID.randomUUID().toString();
    private int clicks;
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(state!=null) clicks=state.getInt("clicks");
        LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(32,32,32,32);
        status=new TextView(this);
        Button click=new Button(this);
        click.setText("点击验证");
        click.setOnClickListener(v->{clicks++;render();});
        body.addView(status);body.addView(click);
        setContentView(body);render();
    }
    private void render() {
        if(status!=null) status.setText("台前调度 · 双宿主只读验证\n任务ID: "+getTaskId()
            +"\n实例: "+instance+"\n点击: "+clicks+"\n多窗: "+isInMultiWindowMode()
            +"\n焦点: "+hasWindowFocus()+"\n请仅使用系统原有多窗入口");
    }
    @Override protected void onResume(){super.onResume();render();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);render();}
    @Override public void onMultiWindowModeChanged(boolean value,android.content.res.Configuration c){
        super.onMultiWindowModeChanged(value,c);render();
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("clicks",clicks);}
}
