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

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.core.view.MenuProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity;
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthLog;
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthLogEntry;

/**
 * Lists the recorded self-hosted health uploads, newest first, with a filter (all / success only /
 * failed only) and a clear action. The data lives in a small JSON file, so it is re-read on every
 * resume and on pull-to-refresh rather than observed.
 */
public class SelfHostedHealthLogActivity extends AbstractGBActivity implements MenuProvider {

    private enum Filter {ALL, SUCCESS, FAILED}

    private Filter filter = Filter.ALL;

    private TextView countView;
    private TextView emptyView;
    private RecyclerView listView;
    private SwipeRefreshLayout refreshLayout;
    private SelfHostedHealthLogAdapter adapter;
    private final List<SelfHostedHealthLogEntry> entries = new ArrayList<>();

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_selfhosted_health_log);
        addMenuProvider(this);

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        countView = findViewById(R.id.logCount);
        emptyView = findViewById(R.id.logEmptyView);
        listView = findViewById(R.id.logListView);
        listView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SelfHostedHealthLogAdapter(this, entries);
        listView.setAdapter(adapter);

        refreshLayout = findViewById(R.id.logRefreshLayout);
        refreshLayout.setOnRefreshListener(() -> {
            reload();
            refreshLayout.setRefreshing(false);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        entries.clear();
        for (final SelfHostedHealthLogEntry entry : SelfHostedHealthLog.read(this)) {
            if (matchesFilter(entry)) {
                entries.add(entry);
            }
        }
        adapter.notifyDataSetChanged();

        countView.setText(getString(R.string.selfhosted_health_log_count, entries.size()));

        final boolean empty = entries.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        emptyView.setText(filter == Filter.ALL
                ? R.string.selfhosted_health_log_empty
                : R.string.selfhosted_health_log_empty_filtered);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private boolean matchesFilter(final SelfHostedHealthLogEntry entry) {
        switch (filter) {
            case SUCCESS:
                return entry.getSuccess();
            case FAILED:
                return !entry.getSuccess();
            default:
                return true;
        }
    }

    @Override
    public void onCreateMenu(@NonNull final Menu menu, @NonNull final MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.menu_selfhosted_health_log, menu);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull final MenuItem menuItem) {
        final int itemId = menuItem.getItemId();
        if (itemId == R.id.selfhosted_health_log_filter) {
            showFilterDialog();
            return true;
        }
        if (itemId == R.id.selfhosted_health_log_clear) {
            confirmClear();
            return true;
        }
        return false;
    }

    private void showFilterDialog() {
        final String[] labels = {
                getString(R.string.selfhosted_health_log_filter_all),
                getString(R.string.selfhosted_health_log_filter_success),
                getString(R.string.selfhosted_health_log_filter_failed),
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.selfhosted_health_log_filter)
                .setSingleChoiceItems(labels, filter.ordinal(), (dialog, which) -> {
                    filter = Filter.values()[which];
                    reload();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.selfhosted_health_log_clear)
                .setMessage(R.string.selfhosted_health_log_clear_confirm)
                .setIcon(R.drawable.ic_warning)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    SelfHostedHealthLog.clear(this);
                    reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
