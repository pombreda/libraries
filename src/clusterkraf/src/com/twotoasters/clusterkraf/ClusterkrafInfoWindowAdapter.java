package com.twotoasters.clusterkraf;

import java.util.HashMap;

import com.google.android.gms.maps.GoogleMap.InfoWindowAdapter;
import com.google.android.gms.maps.model.Marker;

public interface ClusterkrafInfoWindowAdapter extends InfoWindowAdapter {

	void setMarkersClustersMap(HashMap<Marker, ClusterPoint> map);

}
