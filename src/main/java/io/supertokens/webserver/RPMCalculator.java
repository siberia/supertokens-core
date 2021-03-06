/*
 *    Copyright (c) 2020, VRAI Labs and/or its affiliates. All rights reserved.
 *
 *    This software is licensed under the Apache License, Version 2.0 (the
 *    "License") as published by the Apache Software Foundation.
 *
 *    You may not use this file except in compliance with the License. You may
 *    obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package io.supertokens.webserver;

import io.supertokens.Main;
import io.supertokens.ResourceDistributor;
import io.supertokens.backendAPI.Ping;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class RPMCalculator extends ResourceDistributor.SingletonResource {

    private static final String RESOURCE_KEY = "io.supertokens.webserver.RPMCalculator";

    public int RPM_HOUR_DELTA = 3600;    // so that we can change this value to test
    public double RPM_MIN_DELTA = 1;    // so that we can change these values to test

    private final Main main;
    private long rpmHourStartTime = System.currentTimeMillis();
    private int maxRpmWithinAnHour = 0;
    private int numberOfRequestsInCurrMin = 0;
    private long rpmMinStartTime = System.currentTimeMillis();
    private final Object rpmLock = new Object();
    private ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(5);

    private RPMCalculator(Main main) {
        this.main = main;
    }

    public static RPMCalculator getInstance(Main main) {
        RPMCalculator instance = (RPMCalculator) main.getResourceDistributor().getResource(RESOURCE_KEY);
        if (instance == null) {
            instance = (RPMCalculator) main.getResourceDistributor().setResource(RESOURCE_KEY, new RPMCalculator(main));
        }
        return instance;
    }

    Ping.RequestInfo updateRPM() {
        return updateRPM(1);
    }

    public Ping.RequestInfo updateRPM(int numberOfRequests) {
        Ping.RequestInfo result = null;
        try {
            final long timeOfAPI = System.currentTimeMillis();
            synchronized (rpmLock) {
                long deltaHour = timeOfAPI - rpmHourStartTime;
                long deltaMin = timeOfAPI - rpmMinStartTime;
                if (deltaHour < 0 || deltaMin < 0) {
                    return null;
                }
                if (deltaHour > (RPM_HOUR_DELTA * 1000)) {
                    // we have crossed the last hour.
                    result = new Ping.RequestInfo(maxRpmWithinAnHour, rpmHourStartTime);
                    // we have this in a runnable because the below is synchronised with the ping function which may
                    // take
                    // time. So because this is a time sensitive matter (part of tomcat's API thread pool), we do the
                    // below.
                    // Also, this makes it so that we don't take the Ping class lock while holding rpmLock
                    Ping.RequestInfo finalResult = result;
                    executor.execute(() -> Ping.getInstance(main).addToRPM(finalResult));
                    maxRpmWithinAnHour = 0;
                    rpmHourStartTime = timeOfAPI;
                    numberOfRequestsInCurrMin += numberOfRequests;
                } else if (deltaMin > RPM_MIN_DELTA * 1000) {
                    // we have crossed the last min.
                    if (numberOfRequestsInCurrMin > maxRpmWithinAnHour) {
                        maxRpmWithinAnHour = numberOfRequestsInCurrMin;
                    }
                    numberOfRequestsInCurrMin = numberOfRequests;
                    rpmMinStartTime = timeOfAPI;
                } else {
                    numberOfRequestsInCurrMin += numberOfRequests;
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

}
