package org.sliceworkz.eventmodeling.snapshots;

public interface SnapshotCapable<SNAPSHOT_TYPE> {

	SNAPSHOT_TYPE takeSnapshot ( );
	
	void fromSnapshot ( SNAPSHOT_TYPE snapshot );
	
}
