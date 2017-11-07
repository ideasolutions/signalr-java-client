package microsoft.aspnet.signalr.client.transport;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import microsoft.aspnet.signalr.client.ConnectionBase;
import microsoft.aspnet.signalr.client.Constants;
import microsoft.aspnet.signalr.client.LogLevel;
import microsoft.aspnet.signalr.client.Logger;
import microsoft.aspnet.signalr.client.SignalRFuture;
import microsoft.aspnet.signalr.client.UpdateableCancellableFuture;
import microsoft.aspnet.signalr.client.http.InvalidHttpStatusCodeException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.Buffer;

/**
 * Created by giovannimeo on 31/10/17.
 */

public class OkWebsocketTransportNew implements ClientTransport {
    private final OkHttpClient mOkHttpClient;
    private final Logger mLogger;
    private final ExecutorService mSendExecutor = Executors.newSingleThreadExecutor();

    private ConnectionWebSocketListener mCurrentWebSocketListener;
    private SignalRFuture<Void> mAbortFuture;

    protected boolean mStartedAbort = false;

    public OkWebsocketTransportNew(Logger logger) {
        this(new OkHttpClient(), logger);
    }

    public OkWebsocketTransportNew(OkHttpClient okHttpClient, Logger logger) {
        if (logger == null) {
            throw new IllegalArgumentException("logger cannot be null");
        }

        this.mOkHttpClient = okHttpClient;
        this.mOkHttpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build();
        this.mLogger = logger;
    }

    @Override
    public String getName() {
        return "webSockets";
    }

    @Override
    public boolean supportKeepAlive() {
        return true;
    }

    @Override
    public SignalRFuture<NegotiationResponse> negotiate(final ConnectionBase connection) {
        log("Start the negotiation with the server", LogLevel.Information);

        String url = connection.getUrl() + "negotiate" + TransportHelper.getNegotiateQueryString(connection);
        Request request = new Request.Builder()
                .url(url)
                .method(Constants.HTTP_GET, null)
                .build();

        final SignalRFuture<NegotiationResponse> negotiationFuture = new SignalRFuture<NegotiationResponse>();
        final Call call = mOkHttpClient.newCall(request);
        negotiationFuture.onCancelled(new Runnable() {
            @Override
            public void run() {
                call.cancel();
            }
        });

        call.enqueue(new Callback() {
            private void handleFailure(Exception e) {
                log(e);
                negotiationFuture.triggerError(new NegotiationException("There was a problem in the negotiation with the server", e));
            }

            @Override
            public void onFailure(Call call, IOException e) {
                handleFailure(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    log("Response received", LogLevel.Verbose);
                    if (response.isSuccessful())
                        negotiationFuture.setResult(new NegotiationResponse(response.body().string(), connection.getJsonParser()));
                    else
                        throw new InvalidHttpStatusCodeException(response.code(), response.message(), response.headers().toString());
                } catch (InvalidHttpStatusCodeException e) {
                    handleFailure(e);
                } finally {
                    response.body().close();
                }
            }
        });

        return negotiationFuture;
    }

    @Override
    public SignalRFuture<Void> start(ConnectionBase connection, ConnectionType connectionType, DataResultCallback callback) {
        if (mCurrentWebSocketListener != null) {
            mCurrentWebSocketListener.connectionFuture.cancel();
            mCurrentWebSocketListener = null;
        }

        final String connectionString = connectionType == ConnectionType.InitialConnection ? "connect" : "reconnect";

        final String transport = getName();
        final String connectionToken = connection.getConnectionToken();
        final String messageId = connection.getMessageId() != null ? connection.getMessageId() : "";
        final String groupsToken = connection.getGroupsToken() != null ? connection.getGroupsToken() : "";
        final String connectionData = connection.getConnectionData() != null ? connection.getConnectionData() : "";

        String url = null;
        try {
            url = connection.getUrl() + connectionString + '?'
                    + "connectionData=" + URLEncoder.encode(connectionData, "UTF-8")
                    + "&connectionToken=" + URLEncoder.encode(connectionToken, "UTF-8")
                    + "&groupsToken=" + URLEncoder.encode(groupsToken, "UTF-8")
                    + "&messageId=" + URLEncoder.encode(messageId, "UTF-8")
                    + "&transport=" + URLEncoder.encode(transport, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        final Request.Builder reqBuilder = new Request.Builder().get().url(url);
        final UpdateableCancellableFuture<Void> connectionFuture = new UpdateableCancellableFuture<Void>(null);
        final ConnectionWebSocketListener connectionWebSocketListener = new ConnectionWebSocketListener(connectionFuture, callback);

        connectionWebSocketListener.webSocket = mOkHttpClient.newWebSocket(reqBuilder.build(), connectionWebSocketListener);

        connectionFuture.onCancelled(new Runnable() {
            @Override
            public void run() {
//                call.cancel();
//                connectionWebSocketListener.webSocket.cancel();
                if (connectionWebSocketListener.abortCalled) return;
                final WebSocket webSocket = connectionWebSocketListener.webSocket;
                if (webSocket != null) {
                    webSocket.close(1000, "");
                }
                connectionWebSocketListener.webSocket = null;
            }
        });

        mCurrentWebSocketListener = connectionWebSocketListener;
        return connectionFuture;
    }

    @Override
    public SignalRFuture<Void> send(ConnectionBase connection, final String data, DataResultCallback callback) {
        final WebSocket webSocket = mCurrentWebSocketListener.webSocket;
        final SignalRFuture<Void> connectionFuture = mCurrentWebSocketListener.connectionFuture;
        if (webSocket == null) {
            SignalRFuture<Void> future = new SignalRFuture<Void>();
            future.triggerError(new Exception("Web socket isn't available"));
            return future;
        }

        mSendExecutor.submit(new Runnable() {
            @Override
            public void run() {
                if (connectionFuture.isCancelled()) return;
//                try {
                    MediaType TEXT = MediaType.parse("application/vnd.okhttp.websocket+text; charset=utf-8");
                    if (!webSocket.send(/*String.valueOf(RequestBody.create(TEXT, data))*/ data)) {
                        if (!connectionFuture.isCancelled()) connectionFuture.triggerError(new Exception("webSocket.send(data) returned false"));
                    }
//                } catch (IOException e) {
//                    if (!connectionFuture.isCancelled()) connectionFuture.triggerError(e);
//                }
            }
        });

        return new UpdateableCancellableFuture<Void>(null);
    }

    @Override
    public SignalRFuture<Void> abort(ConnectionBase connection) {
        if (mCurrentWebSocketListener != null) mCurrentWebSocketListener.abortCalled = true;

        synchronized (this) {
            if (!mStartedAbort) {
                log("Started aborting", LogLevel.Information);
                mStartedAbort = true;
                String url = connection.getUrl() + "abort" + TransportHelper.getSendQueryString(this, connection);

                Request postRequest = new Request.Builder()
                        .url(url)
                        .method(Constants.HTTP_POST, RequestBody.create(null, ""))
                        .build();


                mAbortFuture = new SignalRFuture<>();
                log("Execute request", LogLevel.Verbose);
                final Call call = mOkHttpClient.newCall(postRequest);
                mAbortFuture.onCancelled(new Runnable() {
                    @Override
                    public void run() {
                        call.cancel();
                    }
                });

                call.enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        log(e);
                        log("Finishing abort", LogLevel.Verbose);
                        mStartedAbort = false;
                        mAbortFuture.triggerError(e);
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        log("Finishing abort", LogLevel.Verbose);
                        mStartedAbort = false;
                        mAbortFuture.setResult(null);
                    }
                });
                return mAbortFuture;

            } else {
                return mAbortFuture;
            }
        }
    }

    protected void log(String message, LogLevel level) {
        mLogger.log(getName() + " - " + message, level);
    }

    protected void log(Throwable error) {
        mLogger.log(getName() + " - Error: " + error.toString(), LogLevel.Critical);
    }

    private static class ConnectionWebSocketListener extends WebSocketListener {
        final SignalRFuture<Void> connectionFuture;
        final DataResultCallback dataCallback;
        WebSocket webSocket;
        boolean abortCalled;

        public ConnectionWebSocketListener(SignalRFuture<Void> connectionFuture, DataResultCallback dataCallback) {
            this.connectionFuture = connectionFuture;
            this.dataCallback = dataCallback;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            this.webSocket = webSocket;
            connectionFuture.setResult(null);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            if (!connectionFuture.isCancelled() && !abortCalled) {
                connectionFuture.cancel();
                connectionFuture.triggerError(t);
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            dataCallback.onData(text);
            //TODO message.close();
        }

//        @Override
//        public void onMessage(ResponseBody message) throws IOException {
//
//        }


        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            if (!connectionFuture.isCancelled() && !abortCalled) {
                connectionFuture.triggerError(new Exception("Received close from server"));
            }
            webSocket = null;
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            super.onClosed(webSocket, code, reason);
        }

        //        @Override
//        public void onPong(Buffer payload) {
//            payload.close();
//        }

//        @Override
//        public void onClose(int code, String reason) {
//
//        }
    }
}
