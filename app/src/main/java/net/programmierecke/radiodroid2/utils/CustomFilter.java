package net.programmierecke.radiodroid2.utils;

/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

/**
 * This is a copy of {@link net.programmierecke.radiodroid2.utils.CustomFilter} with @hide removed from delayer.
 */
public abstract class CustomFilter {
    private static final String LOG_TAG = "CustomFilter";

    private static final String THREAD_NAME = "CustomFilter";
    private static final int FILTER_TOKEN = 0xD0D0F00D;
    private static final int FINISH_TOKEN = 0xDEADBEEF;

    private Handler mThreadHandler;
    private final Handler mResultHandler;

    private Delayer mDelayer;

    private final Object mLock = new Object();

    /**
     * <p>Creates a new asynchronous filter.</p>
     */
    public CustomFilter() {
        mResultHandler = new ResultsHandler();
    }

    /**
     * Provide an interface that decides how long to delay the message for a given query.  Useful
     * for heuristics such as posting a delay for the delete key to avoid doing any work while the
     * user holds down the delete key.
     *
     * @param delayer The delayer.
     */
    public void setDelayer(Delayer delayer) {
        synchronized (mLock) {
            mDelayer = delayer;
        }
    }

    /**
     * <p>Starts an asynchronous filtering operation. Calling this method
     * cancels all previous non-executed filtering requests and posts a new
     * filtering request that will be executed later.</p>
     *
     * @param constraint the constraint used to filter the data
     *
     * @see #filter(CharSequence, net.programmierecke.radiodroid2.utils.CustomFilter.FilterListener)
     */
    public final void filter(CharSequence constraint) {
        filter(constraint, null);
    }

    /**
     * <p>Starts an asynchronous filtering operation. Calling this method
     * cancels all previous non-executed filtering requests and posts a new
     * filtering request that will be executed later.</p>
     *
     * <p>Upon completion, the listener is notified.</p>
     *
     * @param constraint the constraint used to filter the data
     * @param listener a listener notified upon completion of the operation
     *
     * @see #filter(CharSequence)
     * @see #performFiltering(CharSequence)
     * @see #publishResults(CharSequence, net.programmierecke.radiodroid2.utils.CustomFilter.FilterResults)
     */
    public final void filter(CharSequence constraint, net.programmierecke.radiodroid2.utils.CustomFilter.FilterListener listener) {
        synchronized (mLock) {
            if (mThreadHandler == null) {
                HandlerThread thread = new HandlerThread(
                        THREAD_NAME, android.os.Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                mThreadHandler = new RequestHandler(thread.getLooper());
            }

            final long delay = (mDelayer == null) ? 0 : mDelayer.getPostingDelay(constraint);

            Message message = mThreadHandler.obtainMessage(FILTER_TOKEN);

            RequestArguments args = new RequestArguments();
            // make sure we use an immutable copy of the constraint, so that
            // it doesn't change while the filter operation is in progress
            args.constraint = constraint != null ? constraint.toString() : null;
            args.listener = listener;
            message.obj = args;

            mThreadHandler.removeMessages(FILTER_TOKEN);
            mThreadHandler.removeMessages(FINISH_TOKEN);
            mThreadHandler.sendMessageDelayed(message, delay);
        }
    }

    /**
     * <p>Invoked in a worker thread to filter the data according to the
     * constraint. Subclasses must implement this method to perform the
     * filtering operation. Results computed by the filtering operation
     * must be returned as a {@link net.programmierecke.radiodroid2.utils.CustomFilter.FilterResults} that
     * will then be published in the UI thread through
     * {@link #publishResults(CharSequence,
     * net.programmierecke.radiodroid2.utils.CustomFilter.FilterResults)}.</p>
     *
     * <p><strong>Contract:</strong> When the constraint is null, the original
     * data must be restored.</p>
     *
     * @param constraint the constraint used to filter the data
     * @return the results of the filtering operation
     *
     * @see #filter(CharSequence, net.programmierecke.radiodroid2.utils.CustomFilter.FilterListener)
     * @see #publishResults(CharSequence, net.programmierecke.radiodroid2.utils.CustomFilter.FilterResults)
     * @see net.programmierecke.radiodroid2.utils.CustomFilter.FilterResults
     */
    protected abstract net.programmierecke.radiodroid2.utils.CustomFilter.FilterResults performFiltering(CharSequence constraint);

    /**
     * <p>Invoked in the UI thread to publish the filtering results in the
     * user interface. Subclasses must implement this method to display the
     * results computed in {@link #performFiltering}.</p>
     *
     * @param constraint the constraint used to filter the data
     * @param results the results of the filtering operation
     *
     * @see #filter(CharSequence, net.programmierecke.radiodroid2.utils.CustomFilter.FilterListener)
     * @see #performFiltering(CharSequence)
     * @see net.programmierecke.radiodroid2.utils.CustomFilter.FilterResults
     */
    protected abstract void publishResults(CharSequence constraint,
                                           net.programmierecke.radiodroid2.utils.CustomFilter.FilterResults results);

    /**
     * <p>Converts a value from the filtered set into a CharSequence. Subclasses
     * should override this method to convert their results. The default
     * implementation returns an empty String for null values or the default
     * String representation of the value.</p>
     *
     * @param resultValue the value to convert to a CharSequence
     * @return a CharSequence representing the value
     */
    public CharSequence convertResultToString(Object resultValue) {
        return resultValue == null ? "" : resultValue.toString();
    }

    /**
     * <p>Holds the results of a filtering operation. The results are the values
     * computed by the filtering operation and the number of these values.</p>
     */
    protected static class FilterResults {
        public FilterResults() {
            // nothing to see here
        }

        /**
         * <p>Contains all the values computed by the filtering operation.</p>
         */
        public Object values;

        /**
         * <p>Contains the number of values computed by the filtering
         * operation.</p>
         */
        public int count;
    }

    /**
     * <p>Listener used to receive a notification upon completion of a filtering
     * operation.</p>
     */
    public interface FilterListener {
        /**
         * <p>Notifies the end of a filtering operation.</p>
         *
         * @param count the number of values computed by the filter
         */
        void onFilterComplete(int count);
    }

    /**
     * <p>Worker thread handler. When a new filtering request is posted from
     * {@link net.programmierecke.radiodroid2.utils.CustomFilter#filter(CharSequence, net.programmierecke.radiodroid2.utils.CustomFilter.FilterListener)},
     * it is sent to this handler.</p>
     */
    private class RequestHandler extends Handler {
        public RequestHandler(Looper looper) {
            super(looper);
        }

        /**
         * <p>Handles filtering requests by calling
         * {@link net.programmierecke.radiodroid2.utils.CustomFilter#performFiltering} and then sending a message
         * with the results to the results handler.</p>
         *
         * @param msg the filtering request
         */
        public void handleMessage(Message msg) {
            int what = msg.what;
            Message message;
            switch (what) {
                case FILTER_TOKEN:
                    RequestArguments args = (RequestArguments) msg.obj;
                    try {
                        args.results = performFiltering(args.constraint);
                    } catch (Exception e) {
                        args.results = new net.programmierecke.radiodroid2.utils.CustomFilter.FilterResults();
                        Log.w(LOG_TAG, "An exception occured during performFiltering()!", e);
                    } finally {
                        message = mResultHandler.obtainMessage(what);
                        message.obj = args;
                        message.sendToTarget();
                    }

                    synchronized (mLock) {
                        if (mThreadHandler != null) {
                            Message finishMessage = mThreadHandler.obtainMessage(FINISH_TOKEN);
                            mThreadHandler.sendMessageDelayed(finishMessage, 3000);
                        }
                    }
                    break;
                case FINISH_TOKEN:
                    synchronized (mLock) {
                        if (mThreadHandler != null) {
                            mThreadHandler.getLooper().quit();
                            mThreadHandler = null;
                        }
                    }
                    break;
            }
        }
    }

    /**
     * <p>Handles the results of a filtering operation. The results are
     * handled in the UI thread.</p>
     */
    private class ResultsHandler extends Handler {
        /**
         * <p>Messages received from the request handler are processed in the
         * UI thread. The processing involves calling
         * {@link net.programmierecke.radiodroid2.utils.CustomFilter#publishResults(CharSequence,
         * net.programmierecke.radiodroid2.utils.CustomFilter.FilterResults)}
         * to post the results back in the UI and then notifying the listener,
         * if any.</p>
         *
         * @param msg the filtering results
         */
        @Override
        public void handleMessage(Message msg) {
            RequestArguments args = (RequestArguments) msg.obj;

            publishResults(args.constraint, args.results);
            if (args.listener != null) {
                int count = args.results != null ? args.results.count : -1;
                args.listener.onFilterComplete(count);
            }
        }
    }

    /**
     * <p>Holds the arguments of a filtering request as well as the results
     * of the request.</p>
     */
    private static class RequestArguments {
        /**
         * <p>The constraint used to filter the data.</p>
         */
        CharSequence constraint;

        /**
         * <p>The listener to notify upon completion. Can be null.</p>
         */
        net.programmierecke.radiodroid2.utils.CustomFilter.FilterListener listener;

        /**
         * <p>The results of the filtering operation.</p>
         */
        net.programmierecke.radiodroid2.utils.CustomFilter.FilterResults results;
    }

    public interface Delayer {

        /**
         * @param constraint The constraint passed to {@link net.programmierecke.radiodroid2.utils.CustomFilter#filter(CharSequence)}
         * @return The delay that should be used for
         *         {@link Handler#sendMessageDelayed(android.os.Message, long)}
         */
        long getPostingDelay(CharSequence constraint);
    }
}
