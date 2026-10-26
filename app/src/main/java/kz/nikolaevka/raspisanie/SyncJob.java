package kz.nikolaevka.raspisanie;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Фоновое обновление расписания из BilimClass. */
public class SyncJob extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bilim.Result r = Bilim.sync(getApplicationContext());
                jobFinished(params, r == Bilim.Result.NO_NETWORK);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
