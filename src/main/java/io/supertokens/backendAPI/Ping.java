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

// Do not modify after and including this line
package io.supertokens.backendAPI;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.supertokens.Main;
import io.supertokens.ProcessState;
import io.supertokens.ResourceDistributor;
import io.supertokens.ResourceDistributor.SingletonResource;
import io.supertokens.config.Config;
import io.supertokens.cronjobs.memoryWatcher.MemoryWatcher;
import io.supertokens.httpRequest.HttpRequest;
import io.supertokens.httpRequest.HttpResponseException;
import io.supertokens.licenseKey.LicenseKey;
import io.supertokens.licenseKey.LicenseKeyContent;
import io.supertokens.version.Version;
import io.supertokens.version.VersionFile;
import io.supertokens.webserver.RPMCalculator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Ping extends ResourceDistributor.SingletonResource {

    public static final String REQUEST_ID = "io.supertokens.backendAPI.Ping";
    private static final String RESOURCE_KEY = "io.supertokens.backendAPI.Ping";
    private final Main main;
    public boolean hadUsedProVersion = false;

    public List<NameVersion> frontendSDK = new ArrayList<>();
    public List<NameVersion> driver = new ArrayList<>();
    private List<MemoryInfo> memory = new ArrayList<>();
    private List<RequestInfo> rpm = new ArrayList<>();  // requests per min


    private Ping(Main main) {
        this.main = main;
    }

    public static Ping getInstance(Main main) {
        SingletonResource instance = main.getResourceDistributor().getResource(RESOURCE_KEY);
        if (instance == null) {
            instance = main.getResourceDistributor().setResource(RESOURCE_KEY, new Ping(main));
        }
        return (Ping) instance;
    }

    public synchronized void addFrontendSDK(NameVersion info) {
        if (info != null && !this.frontendSDK.contains(info)) {
            this.frontendSDK.add(info);
        }
    }

    public synchronized void addDriver(NameVersion info) {
        if (info != null && !this.driver.contains(info)) {
            this.driver.add(info);
        }
    }

    public synchronized void addToMemory(MemoryInfo info) {
        if (info != null && !this.memory.contains(info)) {
            this.memory.add(info);
        }
    }

    public synchronized void addToRPM(RequestInfo info) {
        if (info != null && !this.rpm.contains(info)) {
            this.rpm.add(info);
        }
    }

    public synchronized void doPing() throws IOException, HttpResponseException {
        addToMemory(MemoryWatcher.getInstance(main).updateMemoryInfo());
        addToRPM(RPMCalculator.getInstance(main).updateRPM(0));
        // get all the parts of the request
        LicenseKeyContent licenseKey = LicenseKey.get(main);
        VersionFile version = Version.getVersion(main);

        String instanceId = main.getProcessId();
        String licenseKeyId = LicenseKey.get(main).getLicenseKeyId();
        String currentlyRunningPlanType = licenseKey.getPlanType().toString();
        NameVersion plugin = new NameVersion(version.getPluginName(), version.getPluginVersion());
        String coreVersion = version.getCoreVersion();
        String pluginInterfaceVersion = version.getPluginInterfaceVersion();
        String appId = licenseKey.getAppId(main);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("instanceId", instanceId);
        requestBody.addProperty("instanceStartTime", main.getProcessStartTime());
        requestBody.addProperty("licenseKeyId", licenseKeyId);
        requestBody.addProperty("currentlyRunningPlanType", currentlyRunningPlanType);
        if (Config.getConfig(main).getCookieDomain(null) != null) {
            requestBody.addProperty("cookieDomain",
                    Config.getConfig(main).getCookieDomain(null));
        } else {
            requestBody.addProperty("cookieDomain", "not set");
        }

        // form JSON body
        JsonObject pluginJson = new JsonObject();
        pluginJson.addProperty("name", plugin.name);
        pluginJson.addProperty("version", plugin.version);
        requestBody.add("plugin", pluginJson);

        requestBody.addProperty("coreVersion", coreVersion);
        requestBody.addProperty("pluginInterfaceVersion", pluginInterfaceVersion);

        JsonArray memoryInfo = new JsonArray();
        this.memory.forEach(item -> {
            JsonObject result = new JsonObject();
            result.addProperty("time", item.time);
            JsonObject totalMemory = new JsonObject();
            totalMemory.addProperty("min", item.minTotal);
            totalMemory.addProperty("max", item.maxTotal);
            totalMemory.addProperty("avg", item.avgTotal);
            result.add("totalMemory", totalMemory);
            JsonObject maxMemory = new JsonObject();
            maxMemory.addProperty("min", item.minMax);
            maxMemory.addProperty("max", item.maxMax);
            maxMemory.addProperty("avg", item.avgMax);
            result.add("maxMemory", maxMemory);
            memoryInfo.add(result);
        });
        requestBody.add("memoryInfo", memoryInfo);

        JsonArray requestsPerMin = new JsonArray();
        this.rpm.forEach(item -> {
            JsonObject result = new JsonObject();
            result.addProperty("value", item.value);
            result.addProperty("time", item.time);
            requestsPerMin.add(result);
        });
        requestBody.add("requestsPerMin", requestsPerMin);

        requestBody.addProperty("hasMovedFromCommercialBinaryToFreeBinary", hadUsedProVersion);

        {
            JsonArray frontendSDKJson = new JsonArray();
            this.frontendSDK.forEach(nameVersion -> {
                JsonObject temp = new JsonObject();
                temp.addProperty("name", nameVersion.name);
                temp.addProperty("version", nameVersion.version);
                frontendSDKJson.add(temp);
            });
            requestBody.add("frontendSDK", frontendSDKJson);
        }

        {
            JsonArray driverJson = new JsonArray();
            this.driver.forEach(nameVersion -> {
                JsonObject temp = new JsonObject();
                temp.addProperty("name", nameVersion.name);
                temp.addProperty("version", nameVersion.version);
                driverJson.add(temp);
            });
            requestBody.add("frontendSDK", driverJson);
        }

        // send request
        HttpRequest.sendJsonPUTRequest(main, REQUEST_ID,
                io.supertokens.utils.Constants.SERVER_URL + "/app/" + appId + "/ping", requestBody, 10000, 10000, 1);

        this.memory = new ArrayList<>();
        this.rpm = new ArrayList<>();

        ProcessState.getInstance(main).addState(ProcessState.PROCESS_STATE.SERVER_PING, null);
    }

    public static class NameVersion {
        final String name;
        final String version;

        public NameVersion(String name, String version) {
            this.name = name;
            this.version = version;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof NameVersion) {
                NameVersion otherNameVersion = (NameVersion) other;
                return otherNameVersion.name.equals(this.name) &&
                        otherNameVersion.version.equals(this.version);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return (this.name + "|" + this.version).hashCode();
        }
    }

    public static class MemoryInfo {
        final double minTotal;
        final double maxTotal;
        final double avgTotal;
        final double minMax;
        final double maxMax;
        final double avgMax;
        final long time;

        public MemoryInfo(double minTotal, double maxTotal, double avgTotal,
                          double minMax, double maxMax, double avgMax, long time) {
            this.minTotal = minTotal;
            this.maxTotal = maxTotal;
            this.avgTotal = avgTotal;
            this.minMax = minMax;
            this.maxMax = maxMax;
            this.avgMax = avgMax;
            this.time = time;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof MemoryInfo) {
                MemoryInfo otherMemoryInfo = (MemoryInfo) other;
                return otherMemoryInfo.time == this.time;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return (this.time + "").hashCode();
        }
    }

    public static class RequestInfo {
        final public int value;
        final public long time;

        public RequestInfo(int value, long time) {
            this.value = value;
            this.time = time;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof RequestInfo) {
                RequestInfo otherMemoryInfo = (RequestInfo) other;
                return otherMemoryInfo.time == this.time;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return (this.time + "").hashCode();
        }
    }
}
// Do not modify before and including this line