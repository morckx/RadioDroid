package net.programmierecke.radiodroid2.players.exoplayer;

import androidx.annotation.NonNull;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.common.util.UnstableApi;

import okhttp3.OkHttpClient;

@UnstableApi
public class RadioDataSourceFactory implements DataSource.Factory {

    private final OkHttpClient httpClient;
    private final TransferListener transferListener;
    private final IcyDataSource.IcyDataSourceListener dataSourceListener;
    private final long retryTimeout;
    private final long retryDelay;

    public RadioDataSourceFactory(@NonNull OkHttpClient httpClient,
                                  @NonNull TransferListener transferListener,
                                  @NonNull IcyDataSource.IcyDataSourceListener dataSourceListener,
                                  long retryTimeout,
                                  long retryDelay) {
        this.httpClient = httpClient;
        this.transferListener = transferListener;
        this.dataSourceListener = dataSourceListener;
        this.retryTimeout = retryTimeout;
        this.retryDelay = retryDelay;
    }

    @Override
    public DataSource createDataSource() {
        return new IcyDataSource(httpClient, transferListener, dataSourceListener);
    }
}
