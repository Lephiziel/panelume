package dev.panelume.reader;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public final class OpeningPerformanceTest {
    @Test public void streamingDigestMatchesCompleteArchive() throws Exception {
        File target=File.createTempFile("panelume-digest",".cbz");
        try {
            byte[] digest;
            try (DownloadArchive writer=new DownloadArchive(target)) {
                for (int i=0;i<3;i++) {
                    writer.zip.putNextEntry(new ZipEntry(i+".jpg"));
                    writer.zip.write(new byte[70000+i]);
                    writer.zip.closeEntry();
                }
                digest=writer.finish();
                try { writer.finish(); fail("Repeated finish accepted"); } catch (IOException expected) {}
            }
            MessageDigest expected=MessageDigest.getInstance("SHA-256");
            try (FileInputStream in=new FileInputStream(target)) {
                byte[] buffer=new byte[8192]; int n;
                while ((n=in.read(buffer))!=-1) expected.update(buffer,0,n);
            }
            assertArrayEquals(expected.digest(),digest);
            try (ZipFile zip=new ZipFile(target)) {
                assertEquals(3,zip.size()); assertEquals(70002,zip.getEntry("2.jpg").getSize());
            }
        } finally { target.delete(); }
    }

    @Test public void medianIsOrderIndependentAndNonMutating() {
        long[] samples={900,100,300,200};
        assertEquals(250,OpenMetrics.median(samples),0);
        assertArrayEquals(new long[]{900,100,300,200},samples);
        assertEquals(300,OpenMetrics.median(new long[]{900,100,300}),0);
        assertTrue(Double.isNaN(OpenMetrics.median(new long[0])));
    }

    @Test public void openingMetricsKeepBoundedSeparateWindows() {
        Context c=RuntimeEnvironment.getApplication();
        c.getSharedPreferences("panelume_open_metrics",Context.MODE_PRIVATE).edit().clear().commit();
        for(int i=1;i<=25;i++) OpenMetrics.recordElapsed(c,OpenMetrics.NETWORK,i*1000);
        OpenMetrics.recordElapsed(c,OpenMetrics.CACHED,500);
        OpenMetrics.recordElapsed(c,OpenMetrics.NETWORK,-1);
        OpenMetrics.recordElapsed(c,OpenMetrics.NETWORK,3600001);
        OpenMetrics.recordElapsed(c,-1,100);
        String network=OpenMetrics.label(c,OpenMetrics.NETWORK);
        assertEquals(c.getString(R.string.open_metrics_distribution,c.getString(R.string.open_metrics_network),25.0,15.5,20),network);
        assertEquals(c.getString(R.string.open_metrics_distribution,c.getString(R.string.open_metrics_cached),.5,.5,1),OpenMetrics.label(c,OpenMetrics.CACHED));
        assertEquals(c.getString(R.string.open_metrics_empty,c.getString(R.string.open_metrics_local)),OpenMetrics.label(c,OpenMetrics.LOCAL));
    }
}
