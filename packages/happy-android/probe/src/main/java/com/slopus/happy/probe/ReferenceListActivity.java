package com.slopus.happy.probe;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A stock RecyclerView of 400 tappable rows ("Row N", 80 dp each): what a
 * list flings like untouched. Tappable as real rows are, so a drag starts on
 * the row and the list takes it over, as it does in the app.
 */
public class ReferenceListActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        int rowHeight = (int) (80 * getResources().getDisplayMetrics().density);
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                TextView text = new TextView(parent.getContext());
                text.setGravity(Gravity.CENTER_VERTICAL);
                text.setPadding(rowHeight / 2, 0, 0, 0);
                text.setOnClickListener(v -> { });
                text.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
                return new RecyclerView.ViewHolder(text) {};
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                ((TextView) holder.itemView).setText("Row " + position);
            }

            @Override
            public int getItemCount() {
                return 400;
            }
        });
        setContentView(list);
    }
}
