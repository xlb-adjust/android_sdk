//
//  ActivityHandler.java
//  AdjustIo
//
//  Created by Christian Wellenbrock on 2013-06-25.
//  Copyright (c) 2013 adeven. All rights reserved.
//  See the file MIT-LICENSE for copying permission.
//

package com.adeven.adjustio;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OptionalDataException;
import java.lang.ref.WeakReference;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

public class ActivityHandler extends HandlerThread {
    private static final String SESSION_STATE_FILENAME = "AdjustIoActivityState";

    private static final long TIMER_INTERVAL      = 1000 * 60 * 1;  // 1 minute
    private static final long SESSION_INTERVAL    = 1000 * 60 * 30; // 30 minutes
    private static final long SUBSESSION_INTERVAL = 1000 * 1;       // one second

    private InternalHandler internalHandler;
    private PackageHandler packageHandler;
    private ActivityState activityState;
    private static ScheduledExecutorService timer;
    private Context context;
    private boolean bufferEvents;

    private String appToken;
    private String macSha1;
    private String macShortMd5;
    private String androidId;       // everything else here could be persisted
    private String fbAttributionId;
    private String userAgent;       // changes, should be updated periodically
    private String clientSdk;

    protected ActivityHandler(Activity activity) {
        super(Logger.LOGTAG, MIN_PRIORITY);
        setDaemon(true);
        start();
        internalHandler = new InternalHandler(getLooper(), this);

        this.context = activity.getApplicationContext();

        Message message = Message.obtain();
        message.arg1 = InternalHandler.INIT;
        internalHandler.sendMessage(message);
    }

    protected void trackSubsessionStart() {
        Message message = Message.obtain();
        message.arg1 = InternalHandler.START;
        internalHandler.sendMessage(message);
    }

    protected void trackSubsessionEnd() {
        Message message = Message.obtain();
        message.arg1 = InternalHandler.END;
        internalHandler.sendMessage(message);
    }

    protected void trackEvent(String eventToken, Map<String, String> parameters) {
        PackageBuilder builder = new PackageBuilder();
        builder.eventToken = eventToken;
        builder.callbackParameters = parameters;

        Message message = Message.obtain();
        message.arg1 = InternalHandler.EVENT;
        message.obj = builder;
        internalHandler.sendMessage(message);
    }

    protected void trackRevenue(double amountInCents, String eventToken, Map<String, String> parameters) {
        PackageBuilder builder = new PackageBuilder();
        builder.amountInCents = amountInCents;
        builder.eventToken = eventToken;
        builder.callbackParameters = parameters;

        Message message = Message.obtain();
        message.arg1 = InternalHandler.REVENUE;
        message.obj = builder;
        internalHandler.sendMessage(message);
    }

    private static final class InternalHandler extends Handler {
        private static final int INIT    = 72630;
        private static final int START   = 72640;
        private static final int END     = 72650;
        private static final int EVENT   = 72660;
        private static final int REVENUE = 72670;

        private final WeakReference<ActivityHandler> sessionHandlerReference;

        protected InternalHandler(Looper looper, ActivityHandler sessionHandler) {
            super(looper);
            this.sessionHandlerReference = new WeakReference<ActivityHandler>(sessionHandler);
        }

        public void handleMessage(Message message) {
            super.handleMessage(message);

            ActivityHandler sessionHandler = sessionHandlerReference.get();
            if (sessionHandler == null) return;

            switch (message.arg1) {
            case INIT:
                sessionHandler.initInternal();
                break;
            case START:
                sessionHandler.startInternal();
                break;
            case END:
                sessionHandler.endInternal();
                break;
            case EVENT:
                PackageBuilder eventBuilder = (PackageBuilder) message.obj;
                sessionHandler.eventInternal(eventBuilder);
                break;
            case REVENUE:
                PackageBuilder revenueBuilder = (PackageBuilder) message.obj;
                sessionHandler.revenueInternal(revenueBuilder);
                break;
            }
        }
    }

    private void initInternal() {
        processApplicationBundle();

        if (!checkAppTokenNotNull(appToken)) return;
        if (!checkAppTokenLength(appToken)) return;
        if (!checkContext(context)) return;
        if (!checkPermissions(context)) return;

        String macAddress = Util.getMacAddress(context);
        String macShort = macAddress.replaceAll(":", "");

        macSha1 = Util.sha1(macAddress);
        macShortMd5 = Util.md5(macShort);
        androidId = Util.getAndroidId(context);
        fbAttributionId = Util.getAttributionId(context);
        userAgent = Util.getUserAgent(context);
        clientSdk = Util.CLIENT_SDK;

        packageHandler = new PackageHandler(context);
        readActivityState();
    }

    private void startInternal() {
        if (!checkAppTokenNotNull(appToken)) return;

        packageHandler.resumeSending();
        startTimer();

        long now = new Date().getTime();

        // very first session
        if (activityState == null) {
            activityState = new ActivityState();
            activityState.sessionCount = 1; // this is the first session
            activityState.createdAt = now;  // starting now

            transferSessionPackage();
            activityState.resetSessionAttributes(now);
            writeActivityState();
            Logger.info("First session");
            return;
        }

        long lastInterval = now - activityState.lastActivity;
        if (lastInterval < 0) {
            Logger.error("Time travel!");
            activityState.lastActivity = now;
            writeActivityState();
            return;
        }

        // new session
        if (lastInterval > SESSION_INTERVAL) {
            activityState.sessionCount++;
            activityState.createdAt = now;
            activityState.lastInterval = lastInterval;

            transferSessionPackage();
            activityState.resetSessionAttributes(now);
            writeActivityState();
            Logger.debug(String.format(Locale.US,
                    "Session %d", activityState.sessionCount));
            return;
        }

        // new subsession
        if (lastInterval > SUBSESSION_INTERVAL) {
            activityState.subsessionCount++;
            Logger.info(String.format(Locale.US,
                    "Started subsession %d of session %d",
                    activityState.subsessionCount,
                    activityState.sessionCount));
        }
        activityState.sessionLength += lastInterval;
        activityState.lastActivity = now;
        writeActivityState();
    }

    private void endInternal() {
        if (!checkAppTokenNotNull(appToken)) return;

        packageHandler.pauseSending();
        stopTimer();
        updateActivityState();
        writeActivityState();
    }

    private void eventInternal(PackageBuilder eventBuilder) {
        if (!checkAppTokenNotNull(appToken)) return;
        if (!checkActivityState(activityState)) return;
        if (!checkEventTokenNotNull(eventBuilder.eventToken)) return;
        if (!checkEventTokenLength(eventBuilder.eventToken)) return;

        long now = new Date().getTime();
        activityState.createdAt = now;
        activityState.eventCount++;
        updateActivityState();

        injectGeneralAttributes(eventBuilder);
        activityState.injectEventAttributes(eventBuilder);
        ActivityPackage eventPackage = eventBuilder.buildEventPackage();
        packageHandler.addPackage(eventPackage);

        if (bufferEvents) {
            Logger.info(String.format("Buffered event%s", eventPackage.suffix));
        } else {
            packageHandler.sendFirstPackage();
        }

        writeActivityState();
        Logger.debug(String.format(Locale.US, "Event %d", activityState.eventCount));
    }


    private void revenueInternal(PackageBuilder revenueBuilder) {
        if (!checkAppTokenNotNull(appToken)) return;
        if (!checkActivityState(activityState)) return;
        if (!checkAmount(revenueBuilder.amountInCents)) return;
        if (!checkEventTokenLength(revenueBuilder.eventToken)) return;

        long now = new Date().getTime();
        activityState.createdAt = now;
        activityState.eventCount++;
        updateActivityState();

        injectGeneralAttributes(revenueBuilder);
        activityState.injectEventAttributes(revenueBuilder);
        ActivityPackage eventPackage = revenueBuilder.buildRevenuePackage();
        packageHandler.addPackage(eventPackage);

        if (bufferEvents) {
            Logger.info(String.format("Buffered revenue%s", eventPackage.suffix));
        } else {
            packageHandler.sendFirstPackage();
        }

        writeActivityState();
        Logger.debug(String.format(Locale.US, "Event %d (revenue)", activityState.eventCount));
    }

    private void updateActivityState() {
        if (!checkActivityState(activityState)) return;

        long now = new Date().getTime();
        long lastInterval = now - activityState.lastActivity;
        if (lastInterval < 0) {
            Logger.error("Time travel!");
            activityState.lastActivity = now;
            return;
        }

        // ignore late updates
        if (lastInterval > SESSION_INTERVAL) return;

        activityState.sessionLength += lastInterval;
        activityState.timeSpent += lastInterval;
        activityState.lastActivity = now;
    }

    private void readActivityState() {
        try {
            FileInputStream inputStream = context.openFileInput(SESSION_STATE_FILENAME);
            BufferedInputStream bufferedStream = new BufferedInputStream(inputStream);
            ObjectInputStream objectStream = new ObjectInputStream(bufferedStream);

            try {
                activityState = (ActivityState) objectStream.readObject();
                Logger.debug(String.format("Read activity state: %s", activityState));
                return;
            }
            catch (ClassNotFoundException e) {
                Logger.error("Failed to find activity state class");
            }
            catch (OptionalDataException e) {}
            catch (IOException e) {
                Logger.error("Failed to read activity states object");
            }
            catch (ClassCastException e) {
                Logger.error("Failed to cast activity state object");
            }
            finally {
                objectStream.close();
            }

        }
        catch (FileNotFoundException e) {
            Logger.verbose("Activity state file not found");
        }
        catch (IOException e) {
            Logger.error("Failed to read activity state file");
        }

        // start with a fresh activity state in case of any exception
        activityState = null;
    }

    private void writeActivityState() {
        try {
            FileOutputStream outputStream = context.openFileOutput(SESSION_STATE_FILENAME, Context.MODE_PRIVATE);
            BufferedOutputStream bufferedStream = new BufferedOutputStream(outputStream);
            ObjectOutputStream objectStream = new ObjectOutputStream(bufferedStream);

            try {
                objectStream.writeObject(activityState);
                Logger.verbose(String.format("Wrote activity state: %s", activityState));
            }
            catch (NotSerializableException e) {
                Logger.error("Failed to serialize activity state");
            }
            finally {
                objectStream.close();
            }

        }
        catch (IOException e) {
            Logger.error(String.format("Failed to write activity state (%s)", e));
        }
    }

    private void transferSessionPackage() {
        PackageBuilder builder = new PackageBuilder();
        injectGeneralAttributes(builder);
        activityState.injectSessionAttributes(builder);
        ActivityPackage sessionPackage = builder.buildSessionPackage();
        packageHandler.addPackage(sessionPackage);
    }

    private void injectGeneralAttributes(PackageBuilder builder) {
        builder.appToken = appToken;
        builder.macShortMd5 = macShortMd5;
        builder.macSha1 = macSha1;
        builder.androidId = androidId;
        builder.fbAttributionId = fbAttributionId;
        builder.userAgent = userAgent;
        builder.clientSdk = clientSdk;
    }

    private void startTimer() {
        if (timer != null) {
            stopTimer();
        }
        timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                timerFired();
            }
        }, 1000, TIMER_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void stopTimer() {
        try {
            timer.shutdown();
        }
        catch (NullPointerException e) {
            Logger.error("No timer found");
        }
    }

    private void timerFired() {
        packageHandler.sendFirstPackage();

        updateActivityState();
        writeActivityState();
    }

    private static boolean checkPermissions(Context context) {
        boolean result = true;

        if (!checkPermission(context, android.Manifest.permission.INTERNET)) {
            Logger.error("Missing permission: INTERNET");
            result = false;
        }
        if (!checkPermission(context, android.Manifest.permission.ACCESS_WIFI_STATE)) {
            Logger.warn("Missing permission: ACCESS_WIFI_STATE");
        }

        return result;
    }

    private void processApplicationBundle() {
        Bundle bundle = getApplicationBundle();
        if (bundle == null) return;

        // appToken
        appToken = bundle.getString("AdjustIoAppToken");

        // logLevel
        String logLevel = bundle.getString("AdjustIoLogLevel");
        if (logLevel == null);
        else if (logLevel.equalsIgnoreCase("verbose")) Logger.setLogLevel(Log.VERBOSE);
        else if (logLevel.equalsIgnoreCase("debug"))   Logger.setLogLevel(Log.DEBUG);
        else if (logLevel.equalsIgnoreCase("info"))    Logger.setLogLevel(Log.INFO);
        else if (logLevel.equalsIgnoreCase("warn"))    Logger.setLogLevel(Log.WARN);
        else if (logLevel.equalsIgnoreCase("error"))   Logger.setLogLevel(Log.ERROR);
        else if (logLevel.equalsIgnoreCase("assert"))  Logger.setLogLevel(Log.ASSERT);
        else Logger.error(String.format("Malformed logLevel '%s'", logLevel));

        // eventBuffering
        bufferEvents = bundle.getBoolean("AdjustIoEventBuffering");
    }

    private Bundle getApplicationBundle() {
        ApplicationInfo applicationInfo = null;
        try {
            String packageName = this.context.getPackageName();
            applicationInfo = this.context.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);
        } catch (NameNotFoundException e) {
            Logger.error("ApplicationInfo not found");
        }
        return applicationInfo.metaData;
    }

    private static boolean checkContext(Context context) {
        if (context == null) {
            Logger.error("Missing context");
            return false;
        }
        return true;
    }

    private static boolean checkPermission(Context context, String permission) {
        int result = context.checkCallingOrSelfPermission(permission);
        boolean granted = (result == PackageManager.PERMISSION_GRANTED);
        return granted;
    }

    private static boolean checkActivityState(ActivityState activityState) {
        if (activityState == null) {
            Logger.error("Missing activity state.");
            return false;
        }
        return true;
    }

    private static boolean checkAppTokenNotNull(String appToken) {
        if (appToken == null) {
            Logger.error("Missing App Token.");
            return false;
        }
        return true;
    }

    private static boolean checkAppTokenLength(String appToken) {
        if (appToken.length() != 12) {
            Logger.error(String.format("Malformed App Token '%s'", appToken));
            return false;
        }
        return true;
    }

    private static boolean checkEventTokenNotNull(String eventToken) {
        if (eventToken == null) {
            Logger.error("Missing Event Token");
            return false;
        }
        return true;
    }

    private static boolean checkEventTokenLength(String eventToken) {
        if (eventToken == null)
            return true;

        if (eventToken.length() != 6) {
            Logger.error(String.format("Malformed Event Token '%s'", eventToken));
            return false;
        }
        return true;
    }

    private static boolean checkAmount(double amount) {
        if (amount <= 0.0) {
            Logger.error(String.format(Locale.US, "Invalid amount %f", amount));
            return false;
        }
        return true;
    }
}
