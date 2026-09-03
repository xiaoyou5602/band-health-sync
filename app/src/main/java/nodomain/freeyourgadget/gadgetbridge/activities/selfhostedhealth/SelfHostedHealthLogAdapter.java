/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities.selfhostedhealth;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthLogEntry;

/**
 * Renders one {@link SelfHostedHealthLogEntry} per row: the data date as the headline, the device
 * underneath, and the upload time / sync-type / record count in a meta line, with the HTTP code
 * trailing. Tapping a row opens the full detail screen.
 */
public class SelfHostedHealthLogAdapter extends RecyclerView.Adapter<SelfHostedHealthLogAdapter.LogViewHolder> {

    private final Context context;
    private final List<SelfHostedHealthLogEntry> items;
    private final SimpleDateFormat rowTimeFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    public SelfHostedHealthLogAdapter(final Context context, final List<SelfHostedHealthLogEntry> items) {
        this.context = context;
        this.items = items;
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(context).inflate(R.layout.item_selfhosted_health_log, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final LogViewHolder holder, final int position) {
        final SelfHostedHealthLogEntry entry = items.get(position);

        holder.date.setText(entry.getDate());
        holder.device.setText(entry.getDeviceName());
        holder.time.setText(rowTimeFormat.format(new Date(entry.getTimestampMs())));
        holder.syncType.setText(entry.getManual()
                ? R.string.selfhosted_health_log_sync_type_manual
                : R.string.selfhosted_health_log_sync_type_auto);
        holder.records.setText(context.getString(R.string.selfhosted_health_log_records_count, entry.getRecords()));
        holder.status.setText(entry.getSuccess()
                ? R.string.selfhosted_health_log_status_success
                : R.string.selfhosted_health_log_status_failed);

        // 0 means the request never reached the server (could not build it, or the network failed),
        // so there is no code to show.
        holder.httpCode.setText(entry.getHttpCode() > 0 ? String.valueOf(entry.getHttpCode()) : "—");

        holder.itemView.setOnClickListener(v -> {
            final Intent intent = new Intent(context, SelfHostedHealthLogDetailActivity.class);
            intent.putExtra(SelfHostedHealthLogDetailActivity.EXTRA_ENTRY, entry.toJson().toString());
            context.startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        final TextView date;
        final TextView device;
        final TextView time;
        final TextView syncType;
        final TextView records;
        final TextView status;
        final TextView httpCode;

        LogViewHolder(final View itemView) {
            super(itemView);
            date = itemView.findViewById(R.id.logItemDate);
            device = itemView.findViewById(R.id.logItemDevice);
            time = itemView.findViewById(R.id.logItemTime);
            syncType = itemView.findViewById(R.id.logItemSyncType);
            records = itemView.findViewById(R.id.logItemRecords);
            status = itemView.findViewById(R.id.logItemStatus);
            httpCode = itemView.findViewById(R.id.logItemHttpCode);
        }
    }
}
