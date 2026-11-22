package com.zyvo.ytgrab;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    public interface Listener {
        void onOpen(HistoryStore.Item item);

        void onDelete(HistoryStore.Item item);
    }

    private final List<HistoryStore.Item> items;
    private final Listener listener;

    public HistoryAdapter(List<HistoryStore.Item> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        HistoryStore.Item it = items.get(position);
        h.title.setText(it.title);
        h.meta.setText(it.meta);
        h.status.setText(it.audio ? "PLAY" : "OPEN");
        ThumbLoader.load(h.thumb, it.thumb);
        h.itemView.setOnClickListener(v -> listener.onOpen(it));
        h.itemView.setOnLongClickListener(v -> {
            listener.onDelete(it);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title;
        final TextView meta;
        final TextView status;

        VH(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.imgThumb);
            title = v.findViewById(R.id.tvTitle);
            meta = v.findViewById(R.id.tvMeta);
            status = v.findViewById(R.id.tvStatus);
        }
    }
}
