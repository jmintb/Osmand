package net.osmand.plus.monitoring;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.app.NotificationCompat;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import net.osmand.Location;
import net.osmand.ValueHolder;
import net.osmand.plus.ApplicationMode;
import net.osmand.plus.ContextMenuAdapter;
import net.osmand.plus.ContextMenuAdapter.OnContextMenuClick;
import net.osmand.plus.NavigationService;
import net.osmand.plus.OsmAndFormatter;
import net.osmand.plus.OsmAndTaskManager.OsmAndTaskRunnable;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.activities.SavingTrackHelper;
import net.osmand.plus.dashboard.tools.DashFragmentData;
import net.osmand.plus.views.MapInfoLayer;
import net.osmand.plus.views.OsmandMapLayer.DrawSettings;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.mapwidgets.TextInfoWidget;

import java.util.List;

import gnu.trove.list.array.TIntArrayList;

public class OsmandMonitoringPlugin extends OsmandPlugin {
	private static final String ID = "osmand.monitoring";
	private static final int notificationId = ID.hashCode();
	public final static String OSMAND_SAVE_SERVICE_ACTION = "OSMAND_SAVE_SERVICE_ACTION";
	private OsmandSettings settings;
	private OsmandApplication app;
	private TextInfoWidget monitoringControl;
	private LiveMonitoringHelper liveMonitoringHelper;
	private boolean isSaving;

	public OsmandMonitoringPlugin(OsmandApplication app) {
		this.app = app;
		liveMonitoringHelper = new LiveMonitoringHelper(app);
		final List<ApplicationMode> am = ApplicationMode.allPossibleValues();
		ApplicationMode.regWidget("monitoring", am.toArray(new ApplicationMode[am.size()]));
		settings = app.getSettings();
	}
	
	@Override
	public void updateLocation(Location location) {
		liveMonitoringHelper.updateLocation(location);
	}
	
	@Override
	public int getLogoResourceId() {
		return R.drawable.ic_action_gps_info;
	}
	
	@Override
	public int getAssetResourceName() {
		return R.drawable.trip_recording;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDescription() {
		return app.getString(R.string.record_plugin_description);
	}

	@Override
	public String getName() {
		return app.getString(R.string.record_plugin_name);
	}

	@Override
	public void registerLayers(MapActivity activity) {
		registerWidget(activity);
	}

	private void registerWidget(MapActivity activity) {
		MapInfoLayer layer = activity.getMapLayers().getMapInfoLayer();
		monitoringControl = createMonitoringControl(activity);
		
		layer.registerSideWidget(monitoringControl,
				R.drawable.ic_action_play_dark, R.string.map_widget_monitoring, "monitoring", false, 18);
		layer.recreateControls();
	}

	@Override
	public void updateLayers(OsmandMapTileView mapView, MapActivity activity) {
		if (isActive()) {
			if (monitoringControl == null) {
				registerWidget(activity);
			}
		} else {
			if (monitoringControl != null) {
				MapInfoLayer layer = activity.getMapLayers().getMapInfoLayer();
				layer.removeSideWidget(monitoringControl);
				layer.recreateControls();
				monitoringControl = null;
			}
		}
	}

	@Override
	public void registerMapContextMenuActions(final MapActivity mapActivity, final double latitude, final double longitude,
			ContextMenuAdapter adapter, Object selectedObj) {
		OnContextMenuClick listener = new OnContextMenuClick() {
			@Override
			public boolean onContextMenuClick(ArrayAdapter<?> adapter, int resId, int pos, boolean isChecked) {
				if (resId == R.string.context_menu_item_add_waypoint) {
					mapActivity.getMapActions().addWaypoint(latitude, longitude);
				}
				return true;
			}
		};
		adapter.item(R.string.context_menu_item_add_waypoint).iconColor(R.drawable.ic_action_gnew_label_dark)
		.listen(listener).reg();
	}
	
	public static final int[] SECONDS = new int[] {0, 1, 2, 3, 5, 10, 15, 30, 60, 90};
	public static final int[] MINUTES = new int[] {2, 3, 5};

	
	@Override
	public Class<? extends Activity> getSettingsActivity() {
		return SettingsMonitoringActivity.class;
	}

	

	/**
	 * creates (if it wasn't created previously) the control to be added on a MapInfoLayer that shows a monitoring state (recorded/stopped)
	 */
	private TextInfoWidget createMonitoringControl(final MapActivity map) {
		monitoringControl = new TextInfoWidget(map) {
			long lastUpdateTime;
			@Override
			public boolean updateInfo(DrawSettings drawSettings) {
				if(isSaving){
					setText(map.getString(R.string.shared_string_save), "");
					setIcons(R.drawable.widget_monitoring_rec_big_day, R.drawable.widget_monitoring_rec_big_night);
					return true;
				}
				String txt = map.getString(R.string.monitoring_control_start);
				String subtxt = null;
				int dn = R.drawable.widget_monitoring_rec_inactive_night;
				int d = R.drawable.widget_monitoring_rec_inactive_day;
				long last = lastUpdateTime;
				final boolean globalRecord = settings.SAVE_GLOBAL_TRACK_TO_GPX.get();
				final boolean isRecording = app.getSavingTrackHelper().getIsRecording();
				float dist = app.getSavingTrackHelper().getDistance();

				//make sure widget always shows recorded track distance if unsaved track exists
				if (dist > 0) {
					last = app.getSavingTrackHelper().getLastTimeUpdated();
					String ds = OsmAndFormatter.getFormattedDistance(dist, map.getMyApplication());
					int ls = ds.lastIndexOf(' ');
					if (ls == -1) {
						txt = ds;
					} else {
						txt = ds.substring(0, ls);
						subtxt = ds.substring(ls + 1);
					}
				}

				if(globalRecord) {
					//indicates global recording (+background recording)
					dn = R.drawable.widget_monitoring_rec_big_night;
					d = R.drawable.widget_monitoring_rec_big_day;
				} else if (isRecording) {
					//indicates (profile-based, configured in settings) recording (looks like is only active during nav in follow mode)
					dn = R.drawable.widget_monitoring_rec_small_night;
					d = R.drawable.widget_monitoring_rec_small_day;
				} else {
					dn = R.drawable.widget_monitoring_rec_inactive_night;
					d = R.drawable.widget_monitoring_rec_inactive_day;
				}

				setText(txt, subtxt);
				setIcons(d, dn);
				if ((last != lastUpdateTime) && (globalRecord || isRecording)) {
					lastUpdateTime = last;
					//blink implementation with 2 indicator states (global logging + profile/navigation logging)
					if (globalRecord) {
						setIcons(R.drawable.widget_monitoring_rec_small_day,
							R.drawable.widget_monitoring_rec_small_night);
					} else {
						setIcons(R.drawable.widget_monitoring_rec_small_day,
								R.drawable.widget_monitoring_rec_small_night);
					}
					
					map.getMyApplication().runInUIThread(new Runnable() {
						@Override
						public void run() {
							if (globalRecord) {
								setIcons(R.drawable.widget_monitoring_rec_big_day,
										R.drawable.widget_monitoring_rec_big_night);
							} else {
								setIcons(R.drawable.widget_monitoring_rec_small_day,
										R.drawable.widget_monitoring_rec_small_night);
							}
						}
					}, 500);
				}
				return true;
			}
		};
		monitoringControl.updateInfo(null);

		// monitoringControl.addView(child);
		monitoringControl.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				controlDialog(map);
			}

			
		});
		return monitoringControl;
	}

	private void controlDialog(final Activity map) {
		final boolean wasTrackMonitored = settings.SAVE_GLOBAL_TRACK_TO_GPX.get();
		
		Builder bld = new AlertDialog.Builder(map);
		final TIntArrayList items = new TIntArrayList();
		if(wasTrackMonitored) {
			items.add(R.string.gpx_monitoring_stop);
			items.add(R.string.gpx_start_new_segment);
			if(settings.LIVE_MONITORING.get()) {
				items.add(R.string.live_monitoring_stop);
			} else if(!settings.LIVE_MONITORING_URL.getProfileDefaultValue().equals(settings.LIVE_MONITORING_URL.get())){
				items.add(R.string.live_monitoring_start);
			}
		} else {
			items.add(R.string.gpx_monitoring_start);
		}
		if(app.getSavingTrackHelper().hasDataToSave()) {
			items.add(R.string.save_current_track);
		}
		String[] strings = new String[items.size()];
		for (int i = 0; i < strings.length; i++) {
			strings[i] = app.getString(items.get(i));
		}
		final int[] holder = new int[] {0};
		final Runnable run = new Runnable() {
			public void run() {
				int which = holder[0];
				int item = items.get(which);
				if(item == R.string.save_current_track){
					saveCurrentTrack();
				} else if(item == R.string.gpx_monitoring_start) {
					if (app.getLocationProvider().checkGPSEnabled(map)) {
						startGPXMonitoring(map);
					}
				} else if(item == R.string.gpx_monitoring_stop) {
					stopRecording();
				} else if(item == R.string.gpx_start_new_segment) {
					app.getSavingTrackHelper().startNewSegment();
				} else if(item == R.string.live_monitoring_stop) {
					settings.LIVE_MONITORING.set(false);
				} else if(item == R.string.live_monitoring_start) {
					final ValueHolder<Integer> vs = new ValueHolder<Integer>();
					vs.value = settings.LIVE_MONITORING_INTERVAL.get();
					showIntervalChooseDialog(map, app.getString(R.string.live_monitoring_interval) + " : %s", 
							app.getString(R.string.save_track_to_gpx_globally), SECONDS, MINUTES,
							null, vs, new OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							settings.LIVE_MONITORING_INTERVAL.set(vs.value);
							settings.LIVE_MONITORING.set(true);
						}
					});
				}
				monitoringControl.updateInfo(null);
			}
		};
		if(strings.length == 1) {
			run.run();
		} else {
			bld.setItems(strings, new OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					holder[0] = which;
					run.run();
				}
			});
			bld.show();
		}
	}

	public void saveCurrentTrack() {
		app.getTaskManager().runInBackground(new OsmAndTaskRunnable<Void, Void, Void>() {

			@Override
			protected Void doInBackground(Void... params) {
				isSaving = true;
				try {
					SavingTrackHelper helper = app.getSavingTrackHelper();
					helper.saveDataToGpx(app.getAppCustomization().getTracksDir());
					helper.close();
				} finally {
					isSaving = false;
				}
				return null;
			}

		}, (Void) null);
	}

	public void stopRecording(){
		settings.SAVE_GLOBAL_TRACK_TO_GPX.set(false);
		if (app.getNavigationService() != null) {
			NotificationManager mNotificationManager =
					(NotificationManager) app.getNavigationService()
							.getSystemService(Context.NOTIFICATION_SERVICE);
			mNotificationManager.cancel(notificationId);
			app.getNavigationService().stopIfNeeded(app, NavigationService.USED_BY_GPX);
		}
	}

	public void startGPXMonitoring(Activity map) {
		final ValueHolder<Integer> vs = new ValueHolder<Integer>();
		final ValueHolder<Boolean> choice = new ValueHolder<Boolean>();
		vs.value = settings.SAVE_GLOBAL_TRACK_INTERVAL.get();
		choice.value = settings.SAVE_GLOBAL_TRACK_REMEMBER.get();
		final Runnable runnable = new Runnable() {
			public void run() {
				app.getSavingTrackHelper().startNewSegment();
				settings.SAVE_GLOBAL_TRACK_INTERVAL.set(vs.value);
				settings.SAVE_GLOBAL_TRACK_TO_GPX.set(true);
				settings.SAVE_GLOBAL_TRACK_REMEMBER.set(choice.value);

				if (settings.SAVE_GLOBAL_TRACK_INTERVAL.get() < 30000) {
					settings.SERVICE_OFF_INTERVAL.set(0);
				} else {
					//Use SERVICE_OFF_INTERVAL > 0 to conserve power for longer GPX recording intervals
					settings.SERVICE_OFF_INTERVAL.set(settings.SAVE_GLOBAL_TRACK_INTERVAL.get());
				}

				app.startNavigationService(NavigationService.USED_BY_GPX);		
			}
		};
		if(choice.value) {
			runnable.run();
		} else {
			showIntervalChooseDialog(map, app.getString(R.string.save_track_interval_globally) + " : %s",
					app.getString(R.string.save_track_to_gpx_globally), SECONDS, MINUTES, choice, vs,
					new OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							runnable.run();
						}
					});
		}

		String stop = map.getResources().getString(R.string.shared_string_control_stop);
		Intent stopIntent = new Intent(NavigationService.OSMAND_STOP_SERVICE_ACTION);
		PendingIntent stopPendingIntent = PendingIntent.getBroadcast(map, 0, stopIntent,
				PendingIntent.FLAG_UPDATE_CURRENT);
		String save = map.getResources().getString(R.string.shared_string_save);
		Intent saveIntent = new Intent(OSMAND_SAVE_SERVICE_ACTION);
		PendingIntent savePendingIntent = PendingIntent.getBroadcast(map, 0, saveIntent,
				PendingIntent.FLAG_UPDATE_CURRENT);

		BroadcastReceiver saveBroadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				final OsmandMonitoringPlugin plugin = OsmandPlugin
						.getEnabledPlugin(OsmandMonitoringPlugin.class);
				if (plugin != null) {
					plugin.saveCurrentTrack();
				}
			}
		};
		map.registerReceiver(saveBroadcastReceiver, new IntentFilter(OSMAND_SAVE_SERVICE_ACTION));

		final NotificationCompat.Builder notificationBuilder =
				new android.support.v7.app.NotificationCompat.Builder(map)
						.setContentTitle(map.getResources().getString(R.string.map_widget_monitoring))
				.setSmallIcon(R.drawable.ic_action_polygom_dark)
//			.setLargeIcon(Helpers.getBitmap(R.drawable.mirakel, getBaseContext()))
				.setOngoing(true)
				.addAction(R.drawable.ic_action_rec_stop, stop, stopPendingIntent)
				.addAction(R.drawable.ic_action_save, save, savePendingIntent);
		NotificationManager mNotificationManager =
				(NotificationManager) map.getSystemService(Context.NOTIFICATION_SERVICE);
		mNotificationManager.notify(notificationId, notificationBuilder.build());
	}

	public static void showIntervalChooseDialog(final Context uiCtx, final String patternMsg,
			String title, final int[] seconds, final int[] minutes, final ValueHolder<Boolean> choice, final ValueHolder<Integer> v, OnClickListener onclick){
		Builder dlg = new AlertDialog.Builder(uiCtx);
		dlg.setTitle(title);
		WindowManager mgr = (WindowManager) uiCtx.getSystemService(Context.WINDOW_SERVICE);
		DisplayMetrics dm = new DisplayMetrics();
		mgr.getDefaultDisplay().getMetrics(dm);
		LinearLayout ll = createIntervalChooseLayout(uiCtx, patternMsg, seconds, minutes,
				choice, v, dm);
		dlg.setView(ll);
		dlg.setPositiveButton(R.string.shared_string_ok, onclick);
		dlg.setNegativeButton(R.string.shared_string_cancel, null);
		dlg.show();
	}

	public static LinearLayout createIntervalChooseLayout(final Context uiCtx,
			final String patternMsg, final int[] seconds, final int[] minutes,
			final ValueHolder<Boolean> choice, final ValueHolder<Integer> v, DisplayMetrics dm) {
		LinearLayout ll = new LinearLayout(uiCtx);
		final TextView tv = new TextView(uiCtx);
		tv.setPadding((int)(7 * dm.density), (int)(3 * dm.density), (int)(7* dm.density), 0);
		tv.setText(String.format(patternMsg, uiCtx.getString(R.string.int_continuosly)));
		
		
		SeekBar sp = new SeekBar(uiCtx);
		sp.setPadding((int)(7 * dm.density), (int)(5 * dm.density), (int)(7* dm.density), 0);
		final int secondsLength = seconds.length;
    	final int minutesLength = minutes.length;
    	sp.setMax(secondsLength + minutesLength - 1);
		sp.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
			}
			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}
			
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				String s;
				if(progress == 0) {
					s = uiCtx.getString(R.string.int_continuosly);
					v.value = 0;
				} else {
					if(progress < secondsLength) {
						s = seconds[progress] + " " + uiCtx.getString(R.string.int_seconds);
						v.value = seconds[progress] * 1000;
					} else {
						s = minutes[progress - secondsLength] + " " + uiCtx.getString(R.string.int_min);
						v.value = minutes[progress - secondsLength] * 60 * 1000;
					}
				}
				tv.setText(String.format(patternMsg, s));
				
			}
		});
		
		for (int i = 0; i < secondsLength + minutesLength - 1; i++) {
			if (i < secondsLength) {
				if (v.value <= seconds[i] * 1000) {
					sp.setProgress(i);
					break;
				}
			} else {
				if (v.value <= minutes[i - secondsLength] * 1000 * 60) {
					sp.setProgress(i);
					break;
				}
			}
		}
		
		ll.setOrientation(LinearLayout.VERTICAL);
		ll.addView(tv);
		ll.addView(sp);
		if (choice != null) {
			final CheckBox cb = new CheckBox(uiCtx);
			cb.setText(R.string.shared_string_remember_my_choice);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
					LayoutParams.WRAP_CONTENT);
			lp.setMargins((int)(7* dm.density), (int)(10* dm.density), (int)(7* dm.density), 0);
			cb.setLayoutParams(lp);
			cb.setOnCheckedChangeListener(new OnCheckedChangeListener() {

				@Override
				public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
					choice.value = isChecked;

				}
			});
			ll.addView(cb);
		}
		return ll;
	}

	@Override
	public DashFragmentData getCardFragment() {
		return new DashFragmentData(DashTrackFragment.TAG, DashTrackFragment.class,
				R.string.record_plugin_name, 11);
	}
}