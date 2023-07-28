/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/
package org.apache.cordova.inappbrowser;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Parcelable;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.LOG;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.StringTokenizer;

@SuppressLint("SetJavaScriptEnabled")
public class InAppBrowser extends CordovaPlugin {

    protected static final String TAG = "mytag";


    private InAppBrowserDialog dialog;
    private WebView inAppWebView;
    private CallbackContext callbackContext;
    private ValueCallback<Uri[]> mUploadCallback;
    private final static int FILECHOOSER_REQUESTCODE = 1;

    private InAppBrowserClient currentClient;

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action          the action to execute.
     * @param args            JSONArry of arguments for the plugin.
     * @param callbackContext the callbackContext used when calling back into JavaScript.
     * @return A PluginResult object with a status and message.
     */
    public boolean execute(String action, CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        if (action.equals("open")) {
            this.callbackContext = callbackContext;
            final String url = args.getString(0);
            String t = args.optString(1);

            final String target = t;

            LOG.d(TAG, "target = " + target);

            this.cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {


                    // CB-6702 InAppBrowser hangs when opening more than one instance
                    if (dialog != null) {
                        dialog.dismiss();
                    }
                    ;

                    // Let's create the main dialog
                    dialog = new InAppBrowserDialog(cordova.getActivity(), android.R.style.Theme_NoTitleBar);
                    dialog.getWindow().getAttributes().windowAnimations = android.R.style.Animation_Dialog;
                    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                    dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

                    dialog.setCancelable(true);
                    dialog.setInAppBroswer(getInAppBrowser());

                    // Main container layout
                    LinearLayout main = new LinearLayout(cordova.getActivity());
                    main.setOrientation(LinearLayout.VERTICAL);


                    // WebView
                    inAppWebView = new WebView(cordova.getActivity());
                    inAppWebView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

                    //1. set background color
                    inAppWebView.setBackgroundColor(Color.parseColor("#FFFFFF"));


                    //2. add downloadlistener
                    inAppWebView.setDownloadListener(new DownloadListener() {
                        @Override
                        public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                            try {
                                Uri uri = Uri.parse(url);
                                inAppWebView.getContext().startActivity(new Intent(Intent.ACTION_VIEW, uri));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });


                    inAppWebView.setWebChromeClient(new WebChromeClient() {
                        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, WebChromeClient.FileChooserParams fileChooserParams) {
                            LOG.d(TAG, "File Chooser 5.0+");
                            // If callback exists, finish it.
                            if (mUploadCallback != null) {
                                mUploadCallback.onReceiveValue(null);
                            }
                            mUploadCallback = filePathCallback;

                            // Create File Chooser Intent
                            Intent content = new Intent(Intent.ACTION_GET_CONTENT);
                            content.addCategory(Intent.CATEGORY_OPENABLE);
                            content.setType("*/*");

                            // Run cordova startActivityForResult
                            cordova.startActivityForResult(InAppBrowser.this, Intent.createChooser(content, "Select File"), FILECHOOSER_REQUESTCODE);
                            return true;
                        }

                        @Override
                        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                            super.onGeolocationPermissionsShowPrompt(origin, callback);
                            callback.invoke(origin, true, false);
                        }
                    });


                    currentClient = new InAppBrowserClient(webView);
                    inAppWebView.setWebViewClient(currentClient);
                    WebSettings settings = inAppWebView.getSettings();
                    settings.setJavaScriptEnabled(true);
                    settings.setJavaScriptCanOpenWindowsAutomatically(true);
                    settings.setBuiltInZoomControls(false);
                    settings.setPluginState(android.webkit.WebSettings.PluginState.ON);

                    // Add postMessage interface
                    class JsObject {
                        @JavascriptInterface
                        public void postMessage(String data) {

                        }
                    }

                    settings.setMediaPlaybackRequiresUserGesture(false);
                    inAppWebView.addJavascriptInterface(new JsObject(), "cordova_iab");
                    settings.setDomStorageEnabled(true);

                    // Enable Thirdparty Cookies
                    CookieManager.getInstance().setAcceptThirdPartyCookies(inAppWebView, true);

                    inAppWebView.loadUrl(url);
                    inAppWebView.getSettings().setLoadWithOverviewMode(true);
                    // Multiple Windows set to true to mitigate Chromium security bug.
                    //  See: https://bugs.chromium.org/p/chromium/issues/detail?id=1083819
                    inAppWebView.getSettings().setSupportMultipleWindows(true);
                    inAppWebView.requestFocus();
                    inAppWebView.requestFocusFromTouch();
                    Log.d("mytag", "Line no: 197");


                    // Add our webview to our main view/layout
                    RelativeLayout webViewLayout = new RelativeLayout(cordova.getActivity());
                    webViewLayout.addView(inAppWebView);
                    main.addView(webViewLayout);


                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                    lp.copyFrom(dialog.getWindow().getAttributes());
                    lp.width = WindowManager.LayoutParams.MATCH_PARENT;
                    lp.height = WindowManager.LayoutParams.MATCH_PARENT;

                    if (dialog != null) {
                        dialog.setContentView(main);
                        dialog.show();
                        dialog.getWindow().setAttributes(lp);
                    }

                    Log.d("mytag", "Line no: 217");

                }
            });
        }
        return true;
    }

    /**
     * Called when the view navigates.
     */
    @Override
    public void onReset() {
        closeDialog();
    }

    /**
     * Called when the system is about to start resuming a previous activity.
     */
    @Override
    public void onPause(boolean multitasking) {
        inAppWebView.onPause();
    }

    /**
     * Called when the activity will start interacting with the user.
     */
    @Override
    public void onResume(boolean multitasking) {
        inAppWebView.onResume();
    }

    /**
     * Called by AccelBroker when listener is to be shut down.
     * Stop listener.
     */
    public void onDestroy() {
        closeDialog();
    }


    /**
     * Put the list of features into a hash map
     *
     * @param optString
     * @return
     */
    private HashMap<String, String> parseFeature(String optString) {

        HashMap<String, String> map = new HashMap<String, String>();
        StringTokenizer features = new StringTokenizer(optString, ",");
        StringTokenizer option;
        while (features.hasMoreElements()) {
            option = new StringTokenizer(features.nextToken(), "=");
            if (option.hasMoreElements()) {
                String key = option.nextToken();
                String value = option.nextToken();
                map.put(key, value);
            }
        }
        return map;

    }


    /**
     * Closes the dialog
     */
    public void closeDialog() {
        this.cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final WebView childView = inAppWebView;
                // The JS protects against multiple calls, so this should happen only when
                // closeDialog() is called by other native code.
                if (childView == null) {
                    return;
                }

                childView.setWebViewClient(new WebViewClient() {
                    // NB: wait for about:blank before dismissing
                    public void onPageFinished(WebView view, String url) {
                        if (dialog != null && !cordova.getActivity().isFinishing()) {
                            dialog.dismiss();
                            dialog = null;
                        }
                    }
                });
                // NB: From SDK 19: "If you call methods on WebView from any thread
                // other than your app's UI thread, it can cause unexpected results."
                // http://developer.android.com/guide/webapps/migrating.html#Threads
                childView.loadUrl("about:blank");

            }
        });
    }

    /**
     * Checks to see if it is possible to go back one page in history, then does so.
     */
    public void goBack() {
        if (this.inAppWebView.canGoBack()) {
            this.inAppWebView.goBack();
        }
    }

    /**
     * Can the web browser go back?
     *
     * @return boolean
     */
    public boolean canGoBack() {
        return this.inAppWebView.canGoBack();
    }


    private InAppBrowser getInAppBrowser() {
        return this;
    }


    /**
     * Receive File Data from File Chooser
     *
     * @param requestCode the requested code from chromeclient
     * @param resultCode  the result code returned from android system
     * @param intent      the data from android file chooser
     */
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        LOG.d(TAG, "onActivityResult");
        // If RequestCode or Callback is Invalid
        if (requestCode != FILECHOOSER_REQUESTCODE || mUploadCallback == null) {
            super.onActivityResult(requestCode, resultCode, intent);
            return;
        }
        mUploadCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, intent));
        mUploadCallback = null;
    }

    /**
     * The webview client receives notifications about appView
     */
    public class InAppBrowserClient extends WebViewClient {
        CordovaWebView webView;

        /**
         * Constructor.
         *
         * @param webView
         */
        public InAppBrowserClient(CordovaWebView webView) {
            this.webView = webView;
        }


        /**
         * Override the URL that should be loaded
         * <p>
         * New (added in API 24)
         * For Android 7 and above.
         *
         * @param webView
         * @param request
         */
        @TargetApi(Build.VERSION_CODES.N)
        @Override
        public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
            return shouldOverrideUrlLoading(request.getUrl().toString(), request.getMethod());
        }

        /**
         * Override the URL that should be loaded
         * <p>
         * This handles a small subset of all the URIs that would be encountered.
         *
         * @param url
         * @param method
         */
        public boolean shouldOverrideUrlLoading(String url, String method) {
            boolean override = false;


            if (url.startsWith(WebView.SCHEME_TEL)) {
                try {
                    Intent intent = new Intent(Intent.ACTION_DIAL);
                    intent.setData(Uri.parse(url));
                    cordova.getActivity().startActivity(intent);
                    override = true;
                } catch (android.content.ActivityNotFoundException e) {
                    LOG.e(TAG, "Error dialing " + url + ": " + e.toString());
                }
            } else if (url.startsWith("geo:") || url.startsWith(WebView.SCHEME_MAILTO) || url.startsWith("market:") || url.startsWith("intent:")) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(url));
                    cordova.getActivity().startActivity(intent);
                    override = true;
                } catch (android.content.ActivityNotFoundException e) {
                    LOG.e(TAG, "Error with " + url + ": " + e.toString());
                }
            }
            // If sms:5551212?body=This is the message
            else if (url.startsWith("sms:")) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);

                    // Get address
                    String address = null;
                    int parmIndex = url.indexOf('?');
                    if (parmIndex == -1) {
                        address = url.substring(4);
                    } else {
                        address = url.substring(4, parmIndex);

                        // If body, then set sms body
                        Uri uri = Uri.parse(url);
                        String query = uri.getQuery();
                        if (query != null) {
                            if (query.startsWith("body=")) {
                                intent.putExtra("sms_body", query.substring(5));
                            }
                        }
                    }
                    intent.setData(Uri.parse("sms:" + address));
                    intent.putExtra("address", address);
                    intent.setType("vnd.android-dir/mms-sms");
                    cordova.getActivity().startActivity(intent);
                    override = true;
                } catch (android.content.ActivityNotFoundException e) {
                    LOG.e(TAG, "Error sending sms " + url + ":" + e.toString());
                }
            }
            return override;
        }


        /**
         * New (added in API 21)
         * For Android 5.0 and above.
         *
         * @param view
         * @param request
         */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return shouldInterceptRequest(request.getUrl().toString(), super.shouldInterceptRequest(view, request), request.getMethod());
        }

        public WebResourceResponse shouldInterceptRequest(String url, WebResourceResponse response, String method) {
            return response;
        }

        /*
         * onPageStarted fires the LOAD_START_EVENT
         *
         * @param view
         * @param url
         * @param favicon
         */
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            String newloc = "";
            if (url.startsWith("http:") || url.startsWith("https:") || url.startsWith("file:")) {
                newloc = url;
            } else {
                // Assume that everything is HTTP at this point, because if we don't specify,
                // it really should be.  Complain loudly about this!!!
                LOG.e(TAG, "Possible Uncaught/Unknown URI");
                newloc = "http://" + url;
            }


        }

        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);


            // CB-10395 InAppBrowser's WebView not storing cookies reliable to local device storage
            CookieManager.getInstance().flush();

            // https://issues.apache.org/jira/browse/CB-11248
            view.clearFocus();
            view.requestFocus();

        }

        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            super.onReceivedError(view, errorCode, description, failingUrl);

        }

    }
}
