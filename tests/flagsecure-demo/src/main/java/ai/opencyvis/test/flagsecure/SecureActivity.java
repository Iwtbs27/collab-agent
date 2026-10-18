package ai.opencyvis.test.flagsecure;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;
import android.view.Gravity;

public class SecureActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        TextView tv = new TextView(this);
        tv.setText("FLAG_SECURE ACTIVE\nThis content should NOT be visible in screenshots");
        tv.setTextSize(24f);
        tv.setTextColor(Color.WHITE);
        tv.setBackgroundColor(Color.RED);
        tv.setGravity(Gravity.CENTER);
        setContentView(tv);
    }
}
