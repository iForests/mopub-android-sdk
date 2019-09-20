// Copyright 2018-2019 Twitter, Inc.
// Licensed under the MoPub SDK License Agreement
// http://www.mopub.com/legal/sdk-license-agreement/

package com.mopub.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * This class exists to wrap InetAddress static calls since java.net classes cannot be mocked
 */
public class InetAddressUtils {
    @NonNull
    public static InetAddress getInetAddressByName(@Nullable final String host) throws UnknownHostException {
        return InetAddress.getByName(host);
    }

    private InetAddressUtils() {
    }
}
