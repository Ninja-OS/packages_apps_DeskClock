/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.deskclock;

import android.app.LoaderManager;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

import com.android.deskclock.alarms.AlarmTimeClickHandler;
import com.android.deskclock.alarms.AlarmUpdateHandler;
import com.android.deskclock.alarms.ScrollHandler;
import com.android.deskclock.alarms.TimePickerCompat;
import com.android.deskclock.alarms.dataadapter.AlarmItemHolder;
import com.android.deskclock.alarms.dataadapter.CollapsedAlarmViewHolder;
import com.android.deskclock.alarms.dataadapter.ExpandedAlarmViewHolder;
import com.android.deskclock.data.DataModel;
import com.android.deskclock.provider.Alarm;
import com.android.deskclock.provider.AlarmInstance;
import com.android.deskclock.uidata.UiDataModel;
import com.android.deskclock.widget.EmptyViewController;
import com.android.deskclock.widget.toast.SnackbarManager;
import com.android.deskclock.widget.toast.ToastManager;

import java.util.ArrayList;
import java.util.List;

import static com.android.deskclock.uidata.UiDataModel.Tab.ALARMS;

/**
 * A fragment that displays a list of alarm time and allows interaction with them.
 */
public final class AlarmClockFragment extends DeskClockFragment implements
        LoaderManager.LoaderCallbacks<Cursor>, ScrollHandler, TimePickerCompat.OnTimeSetListener {

    // This extra is used when receiving an intent to create an alarm, but no alarm details
    // have been passed in, so the alarm page should start the process of creating a new alarm.
    public static final String ALARM_CREATE_NEW_INTENT_EXTRA = "deskclock.create.new";

    // This extra is used when receiving an intent to scroll to specific alarm. If alarm
    // can not be found, and toast message will pop up that the alarm has be deleted.
    public static final String SCROLL_TO_ALARM_INTENT_EXTRA = "deskclock.scroll.to.alarm";

    private static final String KEY_EXPANDED_ID = "expandedId";

    // Updates "Today/Tomorrow" in the UI when midnight passes.
    private final Runnable mMidnightUpdater = new MidnightRunnable();

    // Views
    private ViewGroup mMainLayout;
    private RecyclerView mRecyclerView;

    // Data
    private long mScrollToAlarmId = Alarm.INVALID_ID;
    private long mExpandedAlarmId = Alarm.INVALID_ID;
    private Loader mCursorLoader = null;

    // Controllers
    private ItemAdapter<AlarmItemHolder> mItemAdapter;
    private AlarmUpdateHandler mAlarmUpdateHandler;
    private EmptyViewController mEmptyViewController;
    private AlarmTimeClickHandler mAlarmTimeClickHandler;
    private LinearLayoutManager mLayoutManager;

    /** The public no-arg constructor required by all fragments. */
    public AlarmClockFragment() {
        super(ALARMS);
    }

    @Override
    public void processTimeSet(int hourOfDay, int minute) {
        mAlarmTimeClickHandler.processTimeSet(hourOfDay, minute);
    }

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        mCursorLoader = getLoaderManager().initLoader(0, null, this);
        if (savedState != null) {
            mExpandedAlarmId = savedState.getLong(KEY_EXPANDED_ID, Alarm.INVALID_ID);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedState) {
        // Inflate the layout for this fragment
        final View v = inflater.inflate(R.layout.alarm_clock, container, false);

        mRecyclerView = (RecyclerView) v.findViewById(R.id.alarms_recycler_view);
        mLayoutManager = new LinearLayoutManager(getActivity());
        mRecyclerView.setLayoutManager(mLayoutManager);
        mMainLayout = (ViewGroup) v.findViewById(R.id.main);
        mAlarmUpdateHandler = new AlarmUpdateHandler(getActivity(), this, mMainLayout);
        mEmptyViewController = new EmptyViewController(mMainLayout, mRecyclerView,
                v.findViewById(R.id.alarms_empty_view));
        mAlarmTimeClickHandler = new AlarmTimeClickHandler(this, savedState, mAlarmUpdateHandler,
                this);

        mItemAdapter = new ItemAdapter<>();
        mItemAdapter.withViewTypes(new CollapsedAlarmViewHolder.Factory(inflater),
                null, CollapsedAlarmViewHolder.VIEW_TYPE);
        mItemAdapter.withViewTypes(new ExpandedAlarmViewHolder.Factory(getActivity(), inflater),
                null, ExpandedAlarmViewHolder.VIEW_TYPE);
        mItemAdapter.setOnItemChangedListener(new ItemAdapter.OnItemChangedListener() {
            @Override
            public void onItemChanged(ItemAdapter.ItemHolder<?> holder) {
                if (((AlarmItemHolder) holder).isExpanded()) {
                    if (mExpandedAlarmId != holder.itemId) {
                        // Collapse the prior expanded alarm.
                        final AlarmItemHolder aih = mItemAdapter.findItemById(mExpandedAlarmId);
                        if (aih != null) {
                            aih.collapse();
                        }
                        // Record the freshly expanded alarm.
                        mExpandedAlarmId = holder.itemId;
                    }
                } else if (mExpandedAlarmId == holder.itemId) {
                    // The expanded alarm is now collapsed so update the tracking id.
                    mExpandedAlarmId = Alarm.INVALID_ID;
                }
            }
        });
        final ScrollPositionWatcher scrollPositionWatcher = new ScrollPositionWatcher();
        mRecyclerView.addOnLayoutChangeListener(scrollPositionWatcher);
        mRecyclerView.addOnScrollListener(scrollPositionWatcher);
        mRecyclerView.setAdapter(mItemAdapter);

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();

        // Check if another app asked us to create a blank new alarm.
        final Intent intent = getActivity().getIntent();
        if (intent.hasExtra(ALARM_CREATE_NEW_INTENT_EXTRA)) {
            UiDataModel.getUiDataModel().setSelectedTab(ALARMS);
            if (intent.getBooleanExtra(ALARM_CREATE_NEW_INTENT_EXTRA, false)) {
                // An external app asked us to create a blank alarm.
                startCreatingAlarm();
            }

            // Remove the CREATE_NEW extra now that we've processed it.
            intent.removeExtra(ALARM_CREATE_NEW_INTENT_EXTRA);
        } else if (intent.hasExtra(SCROLL_TO_ALARM_INTENT_EXTRA)) {
            UiDataModel.getUiDataModel().setSelectedTab(ALARMS);

            long alarmId = intent.getLongExtra(SCROLL_TO_ALARM_INTENT_EXTRA, Alarm.INVALID_ID);
            if (alarmId != Alarm.INVALID_ID) {
                setSmoothScrollStableId(alarmId);
                if (mCursorLoader != null && mCursorLoader.isStarted()) {
                    // We need to force a reload here to make sure we have the latest view
                    // of the data to scroll to.
                    mCursorLoader.forceLoad();
                }
            }

            // Remove the SCROLL_TO_ALARM extra now that we've processed it.
            intent.removeExtra(SCROLL_TO_ALARM_INTENT_EXTRA);
        }

        // Schedule a runnable to update the "Today/Tomorrow" values displayed for non-repeating
        // alarms when midnight passes.
        UiDataModel.getUiDataModel().addMidnightCallback(mMidnightUpdater, 100);
    }

    @Override
    public void onPause() {
        super.onPause();
        UiDataModel.getUiDataModel().removePeriodicCallback(mMidnightUpdater);

        // When the user places the app in the background by pressing "home",
        // dismiss the toast bar. However, since there is no way to determine if
        // home was pressed, just dismiss any existing toast bar when restarting
        // the app.
        mAlarmUpdateHandler.hideUndoBar();
    }

    @Override
    public void smoothScrollTo(int position) {
        mLayoutManager.scrollToPositionWithOffset(position, 20);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mAlarmTimeClickHandler.saveInstance(outState);
        outState.putLong(KEY_EXPANDED_ID, mExpandedAlarmId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ToastManager.cancelToast();
    }

    public void setLabel(Alarm alarm, String label) {
        alarm.label = label;
        mAlarmUpdateHandler.asyncUpdateAlarm(alarm, false, true);
    }

    public void setRingtone(Uri ringtoneUri) {
        // Update the default ringtone for future new alarms.
        DataModel.getDataModel().setDefaultAlarmRingtoneUri(ringtoneUri);

        final Alarm alarm = mAlarmTimeClickHandler.getSelectedAlarm();
        if (alarm == null) {
            LogUtils.e("Could not get selected alarm to set ringtone");
            return;
        }
        alarm.alert = ringtoneUri;
        // Save the change to alarm.
        mAlarmUpdateHandler.asyncUpdateAlarm(alarm, false /* popToast */, true /* minorUpdate */);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        return Alarm.getAlarmsCursorLoader(getActivity());
    }

    @Override
    public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor data) {
        final List<AlarmItemHolder> itemHolders = new ArrayList<>(data.getCount());
        for (data.moveToFirst(); !data.isAfterLast(); data.moveToNext()) {
            final Alarm alarm = new Alarm(data);
            final AlarmInstance alarmInstance = alarm.canPreemptivelyDismiss()
                    ? new AlarmInstance(data, true /* joinedTable */) : null;
            final AlarmItemHolder itemHolder =
                    new AlarmItemHolder(alarm, alarmInstance, mAlarmTimeClickHandler);
            itemHolders.add(itemHolder);
        }
        mItemAdapter.setItems(itemHolders);
        mEmptyViewController.setEmpty(data.getCount() == 0);

        if (mExpandedAlarmId != Alarm.INVALID_ID) {
            final AlarmItemHolder aih = mItemAdapter.findItemById(mExpandedAlarmId);
            if (aih != null) {
                aih.expand();
            } else {
                mExpandedAlarmId = Alarm.INVALID_ID;
            }
        }

        if (mScrollToAlarmId != Alarm.INVALID_ID) {
            scrollToAlarm(mScrollToAlarmId);
            setSmoothScrollStableId(Alarm.INVALID_ID);
        }
    }

    /**
     * @param alarmId identifies the alarm to be displayed
     */
    private void scrollToAlarm(long alarmId) {
        final AlarmItemHolder aih = mItemAdapter.findItemById(alarmId);
        if (aih != null) {
            aih.expand();
        } else {
            // Trying to display a deleted alarm should only happen from a missed notification for
            // an alarm that has been marked deleted after use.
            SnackbarManager.show(Snackbar.make(mMainLayout, R.string
                    .missed_alarm_has_been_deleted, Snackbar.LENGTH_LONG));
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> cursorLoader) {
    }

    @Override
    public void setSmoothScrollStableId(long stableId) {
        mScrollToAlarmId = stableId;
    }

    @Override
    public void onFabClick(@NonNull ImageView fab) {
        mAlarmUpdateHandler.hideUndoBar();
        startCreatingAlarm();
    }

    @Override
    public void onUpdateFab(@NonNull ImageView fab) {
        fab.setVisibility(View.VISIBLE);
        fab.setImageResource(R.drawable.ic_add_white_24dp);
        fab.setContentDescription(fab.getResources().getString(R.string.button_alarms));
    }

    @Override
    public void onUpdateFabButtons(@NonNull ImageButton left, @NonNull ImageButton right) {
        left.setVisibility(View.INVISIBLE);
        right.setVisibility(View.INVISIBLE);
    }

    private void startCreatingAlarm() {
        mAlarmTimeClickHandler.clearSelectedAlarm();
        TimePickerCompat.showTimeEditDialog(this, null /* alarm */,
                DateFormat.is24HourFormat(getActivity()));
    }

    /**
     * Updates the vertical scroll state of this tab in the {@link UiDataModel} as the user scrolls
     * the recyclerview or when the size/position of elements within the recyclerview changes.
     */
    private final class ScrollPositionWatcher extends RecyclerView.OnScrollListener
            implements View.OnLayoutChangeListener {
        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            setTabScrolledToTop(Utils.isScrolledToTop(mRecyclerView));
        }

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                int oldLeft, int oldTop, int oldRight, int oldBottom) {
            setTabScrolledToTop(Utils.isScrolledToTop(mRecyclerView));
        }
    }

    /**
     * This runnable executes at midnight and refreshes the display of all alarms. Collapsed alarms
     * that do no repeat will have their "Tomorrow" strings updated to say "Today".
     */
    private final class MidnightRunnable implements Runnable {
        @Override
        public void run() {
            mItemAdapter.notifyDataSetChanged();
        }
    }
}