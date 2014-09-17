package com.twotoasters.clusterkraf;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import android.annotation.SuppressLint;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;

import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMap.CancelableCallback;
import com.google.android.gms.maps.GoogleMap.OnInfoWindowClickListener;
import com.google.android.gms.maps.GoogleMap.OnMarkerClickListener;
import com.google.android.gms.maps.Projection;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

/**
 * Clusters InputPoint objects on a GoogleMap
 */
public class Clusterkraf {

	private final WeakReference<GoogleMap> mapRef;
	private final Options options;
	private final ClusterTransitionsAnimation transitionsAnimation;

	private final InnerCallbackListener innerCallbackListener;

	private ClusterkrafInfoWindowAdapter mClusterkrafInfoWindowAdapter;

	private final ArrayList<InputPoint> points = new ArrayList<InputPoint>();
	private ArrayList<ClusterPoint> currentClusters;
	private ArrayList<Marker> currentMarkers;
	private HashMap<Marker, ClusterPoint> currentClusterPointsByMarker = new HashMap<Marker, ClusterPoint>();
	private HashMap<BasePoint, Marker> currentMarkerByBasePoints = new HashMap<BasePoint, Marker>();
	private ArrayList<ClusterPoint> previousClusters;
	private ArrayList<Marker> previousMarkers;
	private BaseClusteringTaskHost clusteringTaskHost;
	private ClusterTransitionsBuildingTaskHost clusterTransitionsBuildingTaskHost;

	/**
	 * Construct a Clusterkraf instance to manage your map with customized
	 * options and a list of points
	 * 
	 * @param map
	 *            The GoogleMap to be managed by Clusterkraf
	 * @param options
	 *            Customized options
	 */
	public Clusterkraf(final GoogleMap map, final Options options, final ArrayList<InputPoint> points) {
		this.mapRef = new WeakReference<GoogleMap>(map);
		this.options = options;
		this.innerCallbackListener = new InnerCallbackListener(this);
		this.transitionsAnimation = new ClusterTransitionsAnimation(map, options, innerCallbackListener);

		if (points != null) {
			this.points.addAll(points);
		}

		if (map != null) {
			map.setOnCameraChangeListener(innerCallbackListener.clusteringOnCameraChangeListener);
			map.setOnMarkerClickListener(innerCallbackListener);
			map.setOnInfoWindowClickListener(innerCallbackListener);
		}

		showAllClusters();
	}

	public void setClusterkrafInfoWindowAdapter(final ClusterkrafInfoWindowAdapter clusterkrafInfoWindowAdapter) {
		mClusterkrafInfoWindowAdapter = clusterkrafInfoWindowAdapter;
		final GoogleMap map = mapRef.get();
		if (map != null) {
			map.setInfoWindowAdapter(clusterkrafInfoWindowAdapter);
		}
	}

	/**
	 * Add a single InputPoint for clustering
	 * 
	 * @param inputPoint
	 *            The InputPoint object to be clustered
	 */
	public void add(final InputPoint inputPoint) {
		// @TODO: test individually adding points
		if (inputPoint != null) {
			points.add(inputPoint);
			updateClustersAndTransition();
		}
	}

	/**
	 * Add a list of InputPoint objects for clustering
	 * 
	 * @param inputPoints
	 *            The list of InputPoint objects for clustering
	 */
	public void addAll(final ArrayList<InputPoint> inputPoints) {
		if (inputPoints != null) {
			points.addAll(inputPoints);
			updateClustersAndTransition();
		}
	}

	/**
	 * Remove a list of InputPoint objects
	 * 
	 * @param inputPoints
	 *            The list of InputPoint objects to remove
	 */
	public void removeAll(final List<InputPoint> inputPoints) {
		if (inputPoints != null) {
			points.removeAll(inputPoints);
			currentClusters.clear();
			updateClustersAndTransition();
		}
	}

	/**
	 * Remove all existing InputPoint objects and add a new list of InputPoint
	 * objects for clustering
	 * 
	 * @param inputPoints
	 *            The new list of InputPoint objects for clustering
	 */
	public void replace(final ArrayList<InputPoint> inputPoints) {
		clear();
		addAll(inputPoints);
	}

	/**
	 * Remove all Clusterkraf-managed markers from the map
	 */
	public void clear() {
		/**
		 * cancel the background thread clustering task
		 */
		if (clusteringTaskHost != null) {
			clusteringTaskHost.cancel();
			clusteringTaskHost = null;
		}
		/**
		 * cancel the background thread transition building task
		 */
		if (clusterTransitionsBuildingTaskHost != null) {
			clusterTransitionsBuildingTaskHost.cancel();
			clusterTransitionsBuildingTaskHost = null;
		}

		/**
		 * we avoid GoogleMap.clear() so users can manage their own
		 * non-clustered markers on the map.
		 * 
		 * @see http://code.google.com/p/gmaps-api-issues/issues/detail?id=4703
		 */
		if (currentMarkers != null) {
			for (final Marker marker : currentMarkers) {
				marker.remove();
			}
		}
		currentMarkers = null;
		currentClusters = null;
		currentClusterPointsByMarker = null;
		points.clear();
	}

	// TODO: support removing individual InputPoint objects

	private void drawMarkers() {
		final GoogleMap map = mapRef.get();
		if (map != null && currentClusters != null) {
			currentMarkers = new ArrayList<Marker>(currentClusters.size());
			currentClusterPointsByMarker = new HashMap<Marker, ClusterPoint>(currentClusters.size());
			currentMarkerByBasePoints = new HashMap<BasePoint, Marker>(currentClusters.size());
			final MarkerOptionsChooser moc = options.getMarkerOptionsChooser();
			for (final ClusterPoint clusterPoint : currentClusters) {
				final MarkerOptions markerOptions = new MarkerOptions();
				markerOptions.position(clusterPoint.getMapPosition());
				if (moc != null) {
					moc.choose(markerOptions, clusterPoint);
				}
				final Marker marker = map.addMarker(markerOptions);
				currentMarkers.add(marker);
				currentClusterPointsByMarker.put(marker, clusterPoint);

				for (final InputPoint inputPoint : clusterPoint.getPointsInCluster()) {
					currentMarkerByBasePoints.put(inputPoint, marker);
				}
			}

			if (mClusterkrafInfoWindowAdapter != null) {
				mClusterkrafInfoWindowAdapter.setMarkersClustersMap(currentClusterPointsByMarker);
			}
		}
	}

	private void removePreviousMarkers() {
		final GoogleMap map = mapRef.get();
		if (map != null && previousClusters != null && previousMarkers != null) {
			for (final Marker marker : previousMarkers) {
				marker.remove();
			}
			previousMarkers = null;
			previousClusters = null;
		}
	}

	private void updateClustersAndTransition() {
		previousClusters = currentClusters;
		previousMarkers = currentMarkers;

		startClusteringTask();
	}

	private void startClusteringTask() {
		clusteringTaskHost = new UpdateClustersAndTransitionClusteringTaskHost();
		clusteringTaskHost.executeTask();
	}

	private void transitionClusters(final ClusterTransitions clusterTransitions) {
		if (clusterTransitions != null) {
			transitionsAnimation.animate(clusterTransitions);
		}
		clusterTransitionsBuildingTaskHost = null;
	}

	private void startClusterTransitionsBuildingTask(final Projection projection) {
		clusterTransitionsBuildingTaskHost = new ClusterTransitionsBuildingTaskHost();
		clusterTransitionsBuildingTaskHost.executeTask(projection);

		clusteringTaskHost = null;
	}

	private void showAllClusters() {
		if (clusteringTaskHost != null) {
			drawMarkers();
			clusteringTaskHost = null;
		} else {
			clusteringTaskHost = new ShowAllClustersClusteringTaskHost();
			clusteringTaskHost.executeTask();
		}
	}

	/**
	 * Animate the camera so all of the InputPoint objects represented by the
	 * passed ClusterPoint are in view
	 * 
	 * @param clusterPoint
	 */
	public void zoomToBounds(final ClusterPoint clusterPoint) {
		final GoogleMap map = mapRef.get();
		if (map != null && clusterPoint != null) {
			innerCallbackListener.clusteringOnCameraChangeListener.setDirty(System.currentTimeMillis());
			final CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLngBounds(clusterPoint.getBoundsOfInputPoints(), options.getZoomToBoundsPadding());
			map.animateCamera(cameraUpdate, options.getZoomToBoundsAnimationDuration(), null);
		}
	}

	/**
	 * Animate the camera so all of the InputPoint objects represented by the
	 * passed ClusterPoint are in view
	 * 
	 * @param clusterPoint
	 */
	public void zoomToBounds(final InputPoint inputPoint) {
		final GoogleMap map = mapRef.get();
		if (map != null && inputPoint != null) {
			innerCallbackListener.clusteringOnCameraChangeListener.setDirty(System.currentTimeMillis());
			final CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLngZoom(inputPoint.mapPosition, options.getZoomToBoundsPadding());
			map.animateCamera(cameraUpdate, options.getZoomToBoundsAnimationDuration(), null);
		}
	}

	/**
	 * Show the InfoWindow for the passed Marker and ClusterPoint
	 * 
	 * @param marker
	 * @param clusterPoint
	 */
	public void showInfoWindow(final Marker marker, final ClusterPoint clusterPoint) {
		final GoogleMap map = mapRef.get();
		if (map != null && marker != null && clusterPoint != null) {
			final long dirtyUntil = System.currentTimeMillis() + options.getShowInfoWindowAnimationDuration();
			innerCallbackListener.clusteringOnCameraChangeListener.setDirty(dirtyUntil);
			final CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLng(marker.getPosition());
			map.animateCamera(cameraUpdate, options.getShowInfoWindowAnimationDuration(), new CancelableCallback() {

				@Override
				public void onFinish() {
					innerCallbackListener.handler.post(new Runnable() {

						@Override
						public void run() {
							innerCallbackListener.clusteringOnCameraChangeListener.setDirty(0);
						}
					});
				}

				@Override
				public void onCancel() {
					innerCallbackListener.clusteringOnCameraChangeListener.setDirty(0);
				}
			});
			marker.showInfoWindow();
		}
	}

	/**
	 * Show the InfoWindow for the passed BasePoint
	 * 
	 * @param marker
	 * @param basePoint
	 */
	public void showInfoWindow(final BasePoint basePoint) {
		final GoogleMap map = mapRef.get();
		if (map != null && basePoint != null) {
			innerCallbackListener.clusteringOnCameraChangeListener.setDirty(System.currentTimeMillis());
			final CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLngZoom(basePoint.mapPosition, options.getZoomToBoundsPadding());
			map.animateCamera(cameraUpdate, options.getZoomToBoundsAnimationDuration(), new CancelableCallback() {

				@Override
				public void onFinish() {
					innerCallbackListener.handler.post(new Runnable() {
						@Override
						public void run() {
							innerCallbackListener.clusteringOnCameraChangeListener.setDirty(0);
						}
					});
					innerCallbackListener.handler.postDelayed(new Runnable() {
						@Override
						public void run() {
							final Marker marker = currentMarkerByBasePoints.get(basePoint);
							if (marker != null) {
								marker.showInfoWindow();
							}
						}
					}, 400);
				}

				@Override
				public void onCancel() {
					innerCallbackListener.clusteringOnCameraChangeListener.setDirty(0);
				}
			});
		}
	}

	private static class InnerCallbackListener implements ClusteringOnCameraChangeListener.Host, ClusterTransitionsAnimation.Host, OnMarkerClickListener,
			OnInfoWindowClickListener {

		private final WeakReference<Clusterkraf> clusterkrafRef;

		private final Handler handler = new Handler();

		private InnerCallbackListener(final Clusterkraf clusterkraf) {
			clusterkrafRef = new WeakReference<Clusterkraf>(clusterkraf);
			clusteringOnCameraChangeListener = new ClusteringOnCameraChangeListener(this, clusterkraf.options);
		}

		private final ClusteringOnCameraChangeListener clusteringOnCameraChangeListener;

		@Override
		public void onClusteringCameraChange() {
			final Clusterkraf clusterkraf = clusterkrafRef.get();
			if (clusterkraf != null) {
				if (clusterkraf.clusteringTaskHost != null) {
					clusterkraf.clusteringTaskHost.cancel();
					clusterkraf.clusteringTaskHost = null;
				}
				if (clusterkraf.clusterTransitionsBuildingTaskHost != null) {
					clusterkraf.clusterTransitionsBuildingTaskHost.cancel();
					clusterkraf.clusterTransitionsBuildingTaskHost = null;
				}
				clusterkraf.transitionsAnimation.cancel();
				clusterkraf.updateClustersAndTransition();
			}
		}

		/**
		 * @see com.twotoasters.clusterkraf.ClusterTransitionsAnimation.Host#
		 *      onClusterTransitionStarting()
		 */
		@Override
		public void onClusterTransitionStarting() {
			clusteringOnCameraChangeListener.setDirty(System.currentTimeMillis());
		}

		/**
		 * @see com.twotoasters.clusterkraf.ClusterTransitionsAnimation.Host#
		 *      onClusterTransitionStarted()
		 */
		@Override
		public void onClusterTransitionStarted() {
			final Clusterkraf clusterkraf = clusterkrafRef.get();
			if (clusterkraf != null) {
				/**
				 * now that the first frame of the transition has been drawn, we
				 * can remove our previous markers without suffering any
				 * blinking markers
				 */
				clusterkraf.removePreviousMarkers();
			}
		}

		/**
		 * @see com.twotoasters.clusterkraf.ClusterTransitionsAnimation.Host#
		 *      onClusterTransitionFinished()
		 */
		@Override
		public void onClusterTransitionFinished() {
			final Clusterkraf clusterkraf = clusterkrafRef.get();
			if (clusterkraf != null) {
				clusterkraf.drawMarkers();
				/**
				 * now that we have drawn our new set of markers, we can let the
				 * transitionsAnimation know so it can clear its markers
				 */
				clusterkraf.transitionsAnimation.onHostPlottedDestinationClusterPoints();
			}
			clusteringOnCameraChangeListener.setDirty(0);
		}

		/**
		 * @see com.google.android.gms.maps.GoogleMap.OnMarkerClickListener#onMarkerClick
		 *      (com.google.android.gms.maps.model.Marker)
		 */
		@Override
		public boolean onMarkerClick(final Marker marker) {
			boolean handled = false;
			boolean exempt = false;
			final Clusterkraf clusterkraf = clusterkrafRef.get();
			if (clusterkraf != null) {
				ClusterPoint clusterPoint = clusterkraf.currentClusterPointsByMarker.get(marker);
				if (clusterPoint == null) {
					if (clusterkraf.transitionsAnimation.getAnimatedDestinationClusterPoint(marker) != null) {
						exempt = true;
						// animated marker click is not supported
					} else {
						clusterPoint = clusterkraf.transitionsAnimation.getStationaryClusterPoint(marker);
					}
				}
				final OnMarkerClickDownstreamListener downstreamListener = clusterkraf.options.getOnMarkerClickDownstreamListener();
				if (exempt == false && downstreamListener != null) {
					handled = downstreamListener.onMarkerClick(marker, clusterPoint);
				}
				if (exempt == false && handled == false && clusterPoint != null) {
					if (clusterPoint.size() > 1) {
						switch(clusterkraf.options.getClusterClickBehavior()) {
							case ZOOM_TO_BOUNDS:
								clusterkraf.zoomToBounds(clusterPoint);
								handled = true;
								break;
							case SHOW_INFO_WINDOW:
								clusterkraf.showInfoWindow(marker, clusterPoint);
								handled = true;
								break;
							case NO_OP:
								// no-op
								break;
						}
					} else {
						switch(clusterkraf.options.getSinglePointClickBehavior()) {
							case SHOW_INFO_WINDOW:
								clusterkraf.showInfoWindow(marker, clusterPoint);
								handled = true;
								break;
							case NO_OP:
								// no-op
								break;
						}
					}
				}
			}
			return handled || exempt;
		}

		/**
		 * @see com.google.android.gms.maps.GoogleMap.OnInfoWindowClickListener#
		 *      onInfoWindowClick(com.google.android.gms.maps.model.Marker)
		 */
		@Override
		public void onInfoWindowClick(final Marker marker) {
			final Clusterkraf clusterkraf = clusterkrafRef.get();
			if (clusterkraf != null) {
				boolean handled = false;
				final ClusterPoint clusterPoint = clusterkraf.currentClusterPointsByMarker.get(marker);
				final OnInfoWindowClickDownstreamListener downstreamListener = clusterkraf.options.getOnInfoWindowClickDownstreamListener();
				if (downstreamListener != null) {
					handled = downstreamListener.onInfoWindowClick(marker, clusterPoint);
				}
				if (handled == false && clusterPoint != null) {
					if (clusterPoint.size() > 1) {
						switch(clusterkraf.options.getClusterInfoWindowClickBehavior()) {
							case ZOOM_TO_BOUNDS:
								clusterkraf.zoomToBounds(clusterPoint);
								break;
							case HIDE_INFO_WINDOW:
								marker.hideInfoWindow();
								break;
							case NO_OP:
								// no-op
								break;
						}
					} else {
						switch(clusterkraf.options.getSinglePointInfoWindowClickBehavior()) {
							case HIDE_INFO_WINDOW:
								marker.hideInfoWindow();
								break;
							case NO_OP:
								// no-op
								break;
						}
					}
				}

			}

		}
	}

	abstract private class BaseClusteringTaskHost implements ClusteringTask.Host {

		private ClusteringTask task;

		BaseClusteringTaskHost() {
			this.task = new ClusteringTask(this);
		}

		@Override
		public void onClusteringTaskPostExecute(final ClusteringTask.Result result) {
			currentClusters = result.currentClusters;
			onCurrentClustersSet(result);
			task = null;
		}

		public void cancel() {
			final ProcessingListener processingListener = options.getProcessingListener();
			if (processingListener != null) {
				processingListener.onClusteringFinished();
			}
			task.cancel(true);
			task = null;
		}

		@SuppressLint("NewApi")
		public void executeTask() {
			final GoogleMap map = mapRef.get();
			if (map != null) {
				final ProcessingListener processingListener = options.getProcessingListener();
				if (processingListener != null) {
					processingListener.onClusteringStarted();
				}

				final ClusteringTask.Argument arg = new ClusteringTask.Argument();
				arg.projection = map.getProjection();
				arg.options = options;
				arg.points = points;
				arg.previousClusters = previousClusters;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
					task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, arg);
				} else {
					task.execute(arg);
				}
			}

		}

		abstract protected void onCurrentClustersSet(ClusteringTask.Result result);

	}

	private class ShowAllClustersClusteringTaskHost extends BaseClusteringTaskHost {

		@Override
		protected void onCurrentClustersSet(final ClusteringTask.Result result) {
			final ProcessingListener processingListener = options.getProcessingListener();
			if (processingListener != null) {
				processingListener.onClusteringFinished();
			}
			showAllClusters();
		}
	}

	private class UpdateClustersAndTransitionClusteringTaskHost extends BaseClusteringTaskHost {

		@Override
		protected void onCurrentClustersSet(final ClusteringTask.Result result) {
			startClusterTransitionsBuildingTask(result.projection);
		}
	}

	private class ClusterTransitionsBuildingTaskHost implements ClusterTransitionsBuildingTask.Host {

		private ClusterTransitionsBuildingTask task;

		ClusterTransitionsBuildingTaskHost() {
			this.task = new ClusterTransitionsBuildingTask(this);
		}

		@Override
		public void onClusterTransitionsBuildingTaskPostExecute(final ClusterTransitionsBuildingTask.Result result) {
			final ProcessingListener processingListener = options.getProcessingListener();
			if (processingListener != null) {
				processingListener.onClusteringFinished();
			}
			if (result != null) {
				transitionClusters(result.clusterTransitions);
			}
			task = null;
		}

		public void cancel() {
			task.cancel(true);
			task = null;
		}

		@SuppressLint("NewApi")
		public void executeTask(final Projection projection) {
			if (projection != null) {
				final ClusterTransitionsBuildingTask.Argument arg = new ClusterTransitionsBuildingTask.Argument();
				arg.currentClusters = currentClusters;
				arg.previousClusters = previousClusters;
				arg.projection = projection;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
					task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, arg);
				} else {
					task.execute(arg);
				}
			}
		}

	}

	public interface ProcessingListener {
		void onClusteringStarted();

		void onClusteringFinished();
	}

}
