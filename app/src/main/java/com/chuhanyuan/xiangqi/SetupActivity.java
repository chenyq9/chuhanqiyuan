package com.chuhanyuan.xiangqi;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import androidx.appcompat.app.AppCompatActivity;

public class SetupActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences sp = getSharedPreferences("chuhan", MODE_PRIVATE);
        if (sp.getString("base", "").isEmpty()) {
            setContentView(R.layout.activity_setup);
            final EditText b = findViewById(R.id.etBaseUrl);
            final EditText k = findViewById(R.id.etApiKey);
            final EditText m = findViewById(R.id.etModel);
            Button save = findViewById(R.id.btnSave);
            save.setOnClickListener(v -> {
                String base = b.getText().toString().trim();
                if (base.isEmpty()) { b.setError("必填"); return; }
                sp.edit()
                  .putString("base", base)
                  .putString("key", k.getText().toString().trim())
                  .putString("model", m.getText().toString().trim())
                  .apply();
                go();
            });
        } else {
            go();
        }
    }

    private void go() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}