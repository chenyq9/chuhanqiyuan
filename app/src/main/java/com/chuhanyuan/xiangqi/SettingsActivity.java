package com.chuhanyuan.xiangqi;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        final SharedPreferences sp = getSharedPreferences("chuhan", MODE_PRIVATE);
        final EditText b = findViewById(R.id.etBaseUrl);
        final EditText k = findViewById(R.id.etApiKey);
        final EditText m = findViewById(R.id.etModel);
        b.setText(sp.getString("base", ""));
        k.setText(sp.getString("key", ""));
        m.setText(sp.getString("model", ""));
        Button save = findViewById(R.id.btnSave);
        save.setOnClickListener(v -> {
            String base = b.getText().toString().trim();
            if (base.isEmpty()) { b.setError("必填"); return; }
            sp.edit()
              .putString("base", base)
              .putString("key", k.getText().toString().trim())
              .putString("model", m.getText().toString().trim())
              .apply();
            finish();
        });
    }
}