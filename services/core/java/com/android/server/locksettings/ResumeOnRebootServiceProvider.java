/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.locksettings;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelableException;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.service.resumeonreboot.IResumeOnRebootService;
import android.service.resumeonreboot.ResumeOnRebootService;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** @hide */
public class ResumeOnRebootServiceProvider {

    private static final String PROVIDER_PACKAGE = DeviceConfig.getString(
            DeviceConfig.NAMESPACE_OTA, "resume_on_reboot_service_package", "");
    private static final String PROVIDER_REQUIRED_PERMISSION =
            Manifest.permission.BIND_RESUME_ON_REBOOT_SERVICE;
    private static final String TAG = "ResumeOnRebootServiceProvider";

    private final Context mContext;
    private final PackageManager mPackageManager;

    public ResumeOnRebootServiceProvider(Context context) {
        this(context, context.getPackageManager());
    }

    @VisibleForTesting
    public ResumeOnRebootServiceProvider(Context context, PackageManager packageManager) {
        this.mContext = context;
        this.mPackageManager = packageManager;
    }

    @Nullable
    private ServiceInfo resolveService() {
        Intent intent = new Intent();
        intent.setAction(ResumeOnRebootService.SERVICE_INTERFACE);
        if (PROVIDER_PACKAGE != null && !PROVIDER_PACKAGE.equals("")) {
            intent.setPackage(PROVIDER_PACKAGE);
        }

        List<ResolveInfo> resolvedIntents =
                mPackageManager.queryIntentServices(intent, PackageManager.MATCH_SYSTEM_ONLY);
        for (ResolveInfo resolvedInfo : resolvedIntents) {
            if (resolvedInfo.serviceInfo != null
                    && PROVIDER_REQUIRED_PERMISSION.equals(resolvedInfo.serviceInfo.permission)) {
                return resolvedInfo.serviceInfo;
            }
        }
        return null;
    }

    /** Creates a new {@link ResumeOnRebootServiceConnection} */
    @Nullable
    public ResumeOnRebootServiceConnection getServiceConnection() {
        ServiceInfo serviceInfo = resolveService();
        if (serviceInfo == null) {
            return null;
        }
        return new ResumeOnRebootServiceConnection(mContext, serviceInfo.getComponentName());
    }

    /**
     * Connection class used for contacting the registered {@link IResumeOnRebootService}
     */
    public static class ResumeOnRebootServiceConnection {

        private static final String TAG = "ResumeOnRebootServiceConnection";
        private final Context mContext;
        private final ComponentName mComponentName;
        private IResumeOnRebootService mBinder;

        private ResumeOnRebootServiceConnection(Context context,
                @NonNull ComponentName componentName) {
            mContext = context;
            mComponentName = componentName;
        }

        /** Unbind from the service */
        public void unbindService() {
            mContext.unbindService(new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    mBinder = null;

                }
            });
        }

        /** Bind to the service */
        public void bindToService(long timeOut) throws TimeoutException {
            if (mBinder == null || !mBinder.asBinder().isBinderAlive()) {
                CountDownLatch connectionLatch = new CountDownLatch(1);
                Intent intent = new Intent();
                intent.setComponent(mComponentName);
                final boolean success = mContext.bindServiceAsUser(intent, new ServiceConnection() {
                            @Override
                            public void onServiceConnected(ComponentName name, IBinder service) {
                                mBinder = IResumeOnRebootService.Stub.asInterface(service);
                                connectionLatch.countDown();
                            }

                            @Override
                            public void onServiceDisconnected(ComponentName name) {
                            }
                        },
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                        BackgroundThread.getHandler(), UserHandle.SYSTEM);

                if (!success) {
                    Slog.e(TAG, "Binding: " + mComponentName + " u" + UserHandle.SYSTEM
                            + " failed.");
                    return;
                }
                waitForLatch(connectionLatch, "serviceConnection", timeOut);
            }
        }

        /** Wrap opaque blob */
        public byte[] wrapBlob(byte[] unwrappedBlob, long lifeTimeInMillis,
                long timeOutInMillis)
                throws RemoteException, TimeoutException, IOException {
            if (mBinder == null || !mBinder.asBinder().isBinderAlive()) {
                throw new RemoteException("Service not bound");
            }
            CountDownLatch binderLatch = new CountDownLatch(1);
            ResumeOnRebootServiceCallback
                    resultCallback =
                    new ResumeOnRebootServiceCallback(
                            binderLatch);
            mBinder.wrapSecret(unwrappedBlob, lifeTimeInMillis, new RemoteCallback(resultCallback));
            waitForLatch(binderLatch, "wrapSecret", timeOutInMillis);
            if (resultCallback.getResult().containsKey(ResumeOnRebootService.EXCEPTION_KEY)) {
                throwTypedException(resultCallback.getResult().getParcelable(
                        ResumeOnRebootService.EXCEPTION_KEY));
            }
            return resultCallback.mResult.getByteArray(ResumeOnRebootService.WRAPPED_BLOB_KEY);
        }

        /** Unwrap wrapped blob */
        public byte[] unwrap(byte[] wrappedBlob, long timeOut)
                throws RemoteException, TimeoutException, IOException {
            if (mBinder == null || !mBinder.asBinder().isBinderAlive()) {
                throw new RemoteException("Service not bound");
            }
            CountDownLatch binderLatch = new CountDownLatch(1);
            ResumeOnRebootServiceCallback
                    resultCallback =
                    new ResumeOnRebootServiceCallback(
                            binderLatch);
            mBinder.unwrap(wrappedBlob, new RemoteCallback(resultCallback));
            waitForLatch(binderLatch, "unWrapSecret", timeOut);
            if (resultCallback.getResult().containsKey(ResumeOnRebootService.EXCEPTION_KEY)) {
                throwTypedException(resultCallback.getResult().getParcelable(
                        ResumeOnRebootService.EXCEPTION_KEY));
            }
            return resultCallback.getResult().getByteArray(
                    ResumeOnRebootService.UNWRAPPED_BLOB_KEY);
        }

        private void throwTypedException(
                ParcelableException exception)
                throws IOException {
            if (exception.getCause() instanceof IOException) {
                exception.maybeRethrow(IOException.class);
            } else if (exception.getCause() instanceof IllegalStateException) {
                exception.maybeRethrow(IllegalStateException.class);
            } else {
                // This should not happen. Wrap the cause in IllegalStateException so that it
                // doesn't disrupt the exception handling
                throw new IllegalStateException(exception.getCause());
            }
        }

        private void waitForLatch(CountDownLatch latch, String reason, long timeOut)
                throws TimeoutException {
            try {
                if (!latch.await(timeOut, TimeUnit.SECONDS)) {
                    throw new TimeoutException("Latch wait for " + reason + " elapsed");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Latch wait for " + reason + " interrupted");
            }
        }
    }

    private static class ResumeOnRebootServiceCallback implements
            RemoteCallback.OnResultListener {

        private final CountDownLatch mResultLatch;
        private Bundle mResult;

        private ResumeOnRebootServiceCallback(CountDownLatch resultLatch) {
            this.mResultLatch = resultLatch;
        }

        @Override
        public void onResult(@Nullable Bundle result) {
            this.mResult = result;
            mResultLatch.countDown();
        }

        private Bundle getResult() {
            return mResult;
        }
    }
}
