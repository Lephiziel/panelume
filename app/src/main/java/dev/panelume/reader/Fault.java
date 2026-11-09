package dev.panelume.reader;

import android.content.Context;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

final class Fault extends IOException {
    private static final long serialVersionUID = 1L;
    final int message;
    private final transient Object[] args;

    Fault(int message, Object... args) {
        this.message = message;
        this.args = args;
    }

    static String describe(Context context, Throwable failure, int fallback) {
        if (failure instanceof Fault) {
            Fault fault = (Fault) failure;
            return context.getString(fault.message, fault.args);
        }
        if (failure instanceof SocketTimeoutException) return context.getString(R.string.timeout);
        if (failure instanceof UnknownHostException || failure instanceof ConnectException) {
            return context.getString(R.string.network_error);
        }
        return context.getString(fallback);
    }
}
