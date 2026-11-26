package dev.panelume.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.Arrays;

final class OpenMetrics {
    static final int LOCAL = 0, CACHED = 1, NETWORK = 2;
    static final int WINDOW = 20;
    private static final String FILE = "panelume_open_metrics";
    private static final long MAX_MS = 60 * 60 * 1000L;
    private static final int[] LABELS = {R.string.open_metrics_local, R.string.open_metrics_cached, R.string.open_metrics_network};

    private OpenMetrics() {}
    static void record(Context context, int type, long started) {
        if (started <= 0) return;
        recordElapsed(context,type,SystemClock.elapsedRealtime()-started);
    }

    // Version-specific samples avoid comparing an upgraded build against its own old data.
    static synchronized void recordElapsed(Context context,int type,long elapsed) {
        if (type<0 || type>=LABELS.length || elapsed<0 || elapsed>MAX_MS) return;
        SharedPreferences prefs=context.getSharedPreferences(FILE,Context.MODE_PRIVATE);
        long[] previous=samples(prefs,type);
        int start=Math.max(0,previous.length-WINDOW+1);
        StringBuilder next=new StringBuilder();
        for (int i=start;i<previous.length;i++) next.append(previous[i]).append(',');
        next.append(elapsed);
        prefs.edit().putString("samples_031_"+type,next.toString()).apply();
    }

    private static long[] samples(SharedPreferences prefs,int type) {
        String raw=prefs.getString("samples_031_"+type,"");
        if (raw.isEmpty() || raw.length()>WINDOW*9) return new long[0];
        String[] parts=raw.split(",");
        if (parts.length>WINDOW) return new long[0];
        long[] values=new long[parts.length];
        try {
            for (int i=0;i<parts.length;i++) {
                values[i]=Long.parseLong(parts[i]);
                if (values[i]<0 || values[i]>MAX_MS) return new long[0];
            }
        } catch (NumberFormatException e) { return new long[0]; }
        return values;
    }

    static double median(long[] values) {
        if (values.length==0) return Double.NaN;
        long[] ordered=values.clone();
        Arrays.sort(ordered);
        int middle=ordered.length/2;
        return ordered.length%2==1 ? ordered[middle] : (ordered[middle-1]+ordered[middle])/2.0;
    }

    static String label(Context context, int type) {
        SharedPreferences prefs=context.getSharedPreferences(FILE,Context.MODE_PRIVATE);
        long[] values=samples(prefs,type);
        String name=context.getString(LABELS[type]);
        return values.length==0 ? context.getString(R.string.open_metrics_empty,name)
            : context.getString(R.string.open_metrics_distribution,name,values[values.length-1]/1000.0,median(values)/1000.0,values.length);
    }
}
