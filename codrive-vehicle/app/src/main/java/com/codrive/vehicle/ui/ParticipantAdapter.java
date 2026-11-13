package com.codrive.vehicle.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.codrive.vehicle.R;
import com.codrive.vehicle.model.Models;

import java.util.ArrayList;
import java.util.List;

public class ParticipantAdapter extends RecyclerView.Adapter<ParticipantAdapter.Holder> {
    public interface Actions {
        void onPermissions(Models.ParticipantInfo p);

        void onRemove(Models.ParticipantInfo p);
    }

    private final List<Models.ParticipantInfo> items = new ArrayList<>();
    private final Actions actions;
    private boolean restricted;

    public ParticipantAdapter(Actions actions) {
        this.actions = actions;
    }

    public void submit(List<Models.ParticipantInfo> list, boolean restricted) {
        items.clear();
        items.addAll(list);
        this.restricted = restricted;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_participant, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Models.ParticipantInfo p = items.get(position);
        h.name.setText(p.nickname);
        h.state.setText(Labels.connection(p.connection));
        h.state.setTextColor(h.itemView.getResources().getColor(p.isOnline() ? R.color.ok : R.color.warn, null));
        h.role.setText(Labels.role(p.role));
        String[] s = Labels.summarize(p);
        h.allowed.setText(s[0]);
        setOrHide(h.needs, s[1]);
        setOrHide(h.blocked, s[2]);

        h.perm.setEnabled(!restricted);
        h.kick.setEnabled(!restricted);
        h.perm.setOnClickListener(v -> actions.onPermissions(p));
        h.kick.setOnClickListener(v -> actions.onRemove(p));
    }

    private static void setOrHide(TextView v, String text) {
        v.setText(text);
        v.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name, state, role, allowed, needs, blocked;
        final Button perm, kick;

        Holder(View v) {
            super(v);
            name = v.findViewById(R.id.tvName);
            state = v.findViewById(R.id.tvState);
            role = v.findViewById(R.id.tvRole);
            allowed = v.findViewById(R.id.tvAllowed);
            needs = v.findViewById(R.id.tvNeeds);
            blocked = v.findViewById(R.id.tvBlocked);
            perm = v.findViewById(R.id.btnPerm);
            kick = v.findViewById(R.id.btnKick);
        }
    }
}
